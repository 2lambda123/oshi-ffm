/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Contributions to this file must be licensed under the Apache 2.0 license or a compatible open source license.
 */
package ooo.oshi.software.os.mac;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static ooo.oshi.foreign.mac.SystemLibrary.PROC_PIDTASKALLINFO;
import static ooo.oshi.foreign.mac.SystemLibrary.errno;
import static ooo.oshi.foreign.mac.SystemLibrary.procTaskAllInfo;
import static ooo.oshi.foreign.mac.SystemLibrary.proc_pidinfo;
import static ooo.oshi.software.os.OSProcess.State.INVALID;
import static ooo.oshi.software.os.OSProcess.State.NEW;
import static ooo.oshi.software.os.OSProcess.State.OTHER;
import static ooo.oshi.software.os.OSProcess.State.RUNNING;
import static ooo.oshi.software.os.OSProcess.State.SLEEPING;
import static ooo.oshi.software.os.OSProcess.State.STOPPED;
import static ooo.oshi.software.os.OSProcess.State.WAITING;
import static ooo.oshi.software.os.OSProcess.State.ZOMBIE;
import static ooo.oshi.util.Memoizer.memoize;

import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ooo.oshi.annotation.concurrent.ThreadSafe;
import ooo.oshi.foreign.mac.SystemLibrary;
import ooo.oshi.software.os.OSThread;
import ooo.oshi.software.os.common.AbstractOSProcess;
import ooo.oshi.util.platform.mac.SysctlUtil;
import ooo.oshi.util.tuples.Pair;

/**
 * OSProcess implementation
 */
@ThreadSafe
public class MacOSProcess extends AbstractOSProcess {

    private static final Logger LOG = LoggerFactory.getLogger(MacOSProcess.class);

    private static final int ARGMAX = SysctlUtil.sysctl("kern.argmax", 0);

    // 64-bit flag
    private static final int P_LP64 = 0x4;
    /*
     * macOS States:
     */
    private static final int SSLEEP = 1; // sleeping on high priority
    private static final int SWAIT = 2; // sleeping on low priority
    private static final int SRUN = 3; // running
    private static final int SIDL = 4; // intermediate state in process creation
    private static final int SZOMB = 5; // intermediate state in process termination
    private static final int SSTOP = 6; // process being traced

    /*
     * For process info structures
     */
    private static final PathElement PBSD = groupElement("pbsd");
    private static final long STATUS_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_status"));
    private static final long PPID_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_ppid"));
    private static final long UID_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_uid"));
    private static final long GID_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_gid"));
    private static final long START_TVSEC_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_start_tvsec"));
    private static final long START_TVUSEC_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_start_tvusec"));
    private static final long NFILES_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_nfiles"));
    private static final long FLAGS_OFFSET = procTaskAllInfo.byteOffset(PBSD, groupElement("pbi_flags"));

    private static final PathElement PTINFO = groupElement("ptinfo");
    private static final long TNUM_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_threadnum"));
    private static final long PRI_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_priority"));
    private static final long VSZ_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_virtual_size"));
    private static final long RSS_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_resident_size"));
    private static final long SYS_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_total_system"));
    private static final long USR_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_total_user"));
    private static final long PGIN_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_pageins"));
    private static final long FAULTS_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_faults"));
    private static final long CSW_OFFSET = procTaskAllInfo.byteOffset(PTINFO, groupElement("pti_csw"));

    private int majorVersion;
    private int minorVersion;

    private Supplier<String> commandLine = memoize(this::queryCommandLine);
    private Supplier<Pair<List<String>, Map<String, String>>> argsEnviron = memoize(this::queryArgsAndEnvironment);

    private String name = "";
    private String path = "";
    private String currentWorkingDirectory;
    private String user;
    private String userID;
    private String group;
    private String groupID;
    private State state = INVALID;
    private int parentProcessID;
    private int threadCount;
    private int priority;
    private long virtualSize;
    private long residentSetSize;
    private long kernelTime;
    private long userTime;
    private long startTime;
    private long upTime;
    private long bytesRead;
    private long bytesWritten;
    private long openFiles;
    private int bitness;
    private long minorFaults;
    private long majorFaults;
    private long contextSwitches;

    public MacOSProcess(int pid, int major, int minor) {
        super(pid);
        this.majorVersion = major;
        this.minorVersion = minor;
        updateAttributes();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPath() {
        return this.path;
    }

    @Override
    public String getCommandLine() {
        return this.commandLine.get();
    }

    private String queryCommandLine() {
        return String.join(" ", getArguments()).trim();
    }

    @Override
    public List<String> getArguments() {
        return argsEnviron.get().getA();
    }

    @Override
    public Map<String, String> getEnvironmentVariables() {
        return argsEnviron.get().getB();
    }

    private Pair<List<String>, Map<String, String>> queryArgsAndEnvironment() {
        int pid = getProcessID();
        // Set up return objects
        List<String> args = new ArrayList<>();
        // API does not specify any particular order of entries, but it is reasonable to
        // maintain whatever order the OS provided to the end user
        Map<String, String> env = new LinkedHashMap<>();

        // Get command line via sysctl CTL_KERN, KERN_PROCARGS2
        MemorySegment m = SysctlUtil.sysctl(new int[] { 1, 49, pid }, ARGMAX);
        if (m != null) {
            // Procargs contains an int representing total # of args, followed by a
            // null-terminated execpath string and then the arguments, each
            // null-terminated (possible multiple consecutive nulls),
            // The execpath string is also the first arg.
            // Following this is an int representing total # of env, followed by
            // null-terminated envs in similar format
            int nargs = m.get(ValueLayout.JAVA_INT, 0);
            // Sanity check
            if (nargs > 0 && nargs <= 1024) {
                // Skip first int (containing value of nargs)
                long offset = SystemLibrary.INT_SIZE;
                // Skip exec_command and null terminator, as it's duplicated in first arg
                String cmdLine = m.getUtf8String(offset);
                offset += cmdLine.getBytes(StandardCharsets.UTF_8).length + 1;
                // Build each arg and add to list
                while (offset < ARGMAX) {
                    // Grab a string. This should go until the null terminator
                    String arg = m.getUtf8String(offset);
                    if (nargs-- > 0) {
                        // If we havent found nargs yet, it's an arg
                        args.add(arg);
                    } else {
                        // otherwise it's an env
                        int idx = arg.indexOf('=');
                        if (idx > 0) {
                            env.put(arg.substring(0, idx), arg.substring(idx + 1));
                        }
                    }
                    // Advance offset to next null
                    offset += arg.getBytes(StandardCharsets.UTF_8).length + 1;
                }
            } else {
                // Don't warn for pid 0
                if (pid > 0) {
                    LOG.warn(
                            "Failed sysctl call for process arguments (kern.procargs2), process {} may not exist. Error code: {}",
                            pid, errno());
                }
            }
        }
        return new Pair<>(Collections.unmodifiableList(args), Collections.unmodifiableMap(env));
    }

    @Override
    public String getCurrentWorkingDirectory() {
        return this.currentWorkingDirectory;
    }

    @Override
    public String getUser() {
        return this.user;
    }

    @Override
    public String getUserID() {
        return this.userID;
    }

    @Override
    public String getGroup() {
        return this.group;
    }

    @Override
    public String getGroupID() {
        return this.groupID;
    }

    @Override
    public State getState() {
        return this.state;
    }

    @Override
    public int getParentProcessID() {
        return this.parentProcessID;
    }

    @Override
    public int getThreadCount() {
        return this.threadCount;
    }

    @Override
    public List<OSThread> getThreadDetails() {
        long now = System.currentTimeMillis();
        List<OSThread> details = new ArrayList<>();
        /*-
        List<ThreadStats> stats = ThreadInfo.queryTaskThreads(getProcessID());
        for (ThreadStats stat : stats) {
            // For long running threads the start time calculation can overestimate
            long start = now - stat.getUpTime();
            if (start < this.getStartTime()) {
                start = this.getStartTime();
            }
            details.add(new MacOSThread(getProcessID(), stat.getThreadId(), stat.getState(), stat.getSystemTime(),
                    stat.getUserTime(), start, now - start, stat.getPriority()));
        }
        */
        return details;
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public long getVirtualSize() {
        return this.virtualSize;
    }

    @Override
    public long getResidentSetSize() {
        return this.residentSetSize;
    }

    @Override
    public long getKernelTime() {
        return this.kernelTime;
    }

    @Override
    public long getUserTime() {
        return this.userTime;
    }

    @Override
    public long getUpTime() {
        return this.upTime;
    }

    @Override
    public long getStartTime() {
        return this.startTime;
    }

    @Override
    public long getBytesRead() {
        return this.bytesRead;
    }

    @Override
    public long getBytesWritten() {
        return this.bytesWritten;
    }

    @Override
    public long getOpenFiles() {
        return this.openFiles;
    }

    @Override
    public int getBitness() {
        return this.bitness;
    }

    @Override
    public long getAffinityMask() {
        // macOS doesn't do affinity. Return a bitmask of the current processors.
        int logicalProcessorCount = SysctlUtil.sysctl("hw.logicalcpu", 1);
        return logicalProcessorCount < 64 ? (1L << logicalProcessorCount) - 1 : -1L;
    }

    @Override
    public long getMinorFaults() {
        return this.minorFaults;
    }

    @Override
    public long getMajorFaults() {
        return this.majorFaults;
    }

    @Override
    public long getContextSwitches() {
        return this.contextSwitches;
    }

    @Override
    public boolean updateAttributes() {
        long now = System.currentTimeMillis();
        SegmentAllocator allocator = SegmentAllocator.implicitAllocator();
        int size = (int) procTaskAllInfo.byteSize();
        MemorySegment m = allocator.allocate(size);
        if (0 > proc_pidinfo(getProcessID(), PROC_PIDTASKALLINFO, 0, m, size)) {
            this.state = INVALID;
            return false;
        }
        // Check threadcount first: 0 is invalid
        this.threadCount = m.get(JAVA_INT, TNUM_OFFSET);
        if (0 == this.threadCount) {
            this.state = INVALID;
            return false;
        }
        /*-
            try (Memory buf = new Memory(SystemB.PROC_PIDPATHINFO_MAXSIZE)) {
                if (0 < SystemB.INSTANCE.proc_pidpath(getProcessID(), buf, SystemB.PROC_PIDPATHINFO_MAXSIZE)) {
                    this.path = buf.getString(0).trim();
                    // Overwrite name with last part of path
                    String[] pathSplit = this.path.split("/");
                    if (pathSplit.length > 0) {
                        this.name = pathSplit[pathSplit.length - 1];
                    }
                }
            }
            if (this.name.isEmpty()) {
                // pbi_comm contains first 16 characters of name
                this.name = Native.toString(taskAllInfo.pbsd.pbi_comm, StandardCharsets.UTF_8);
            }             
         */

        switch (m.get(JAVA_INT, STATUS_OFFSET)) {
        case SSLEEP:
            this.state = SLEEPING;
            break;
        case SWAIT:
            this.state = WAITING;
            break;
        case SRUN:
            this.state = RUNNING;
            break;
        case SIDL:
            this.state = NEW;
            break;
        case SZOMB:
            this.state = ZOMBIE;
            break;
        case SSTOP:
            this.state = STOPPED;
            break;
        default:
            this.state = OTHER;
            break;
        }

        this.parentProcessID = m.get(JAVA_INT, PPID_OFFSET);
        this.userID = Integer.toString(m.get(JAVA_INT, UID_OFFSET));
        /*-
        Passwd pwuid = SystemB.INSTANCE.getpwuid(taskAllInfo.pbsd.pbi_uid);
        if (pwuid != null) {
            this.user = pwuid.pw_name;
        }
        */
        this.groupID = Integer.toString(m.get(JAVA_INT, GID_OFFSET));
        /*-
        Group grgid = SystemB.INSTANCE.getgrgid(taskAllInfo.pbsd.pbi_gid);
        if (grgid != null) {
            this.group = grgid.gr_name;
        }
        */
        this.priority = m.get(JAVA_INT, PRI_OFFSET);
        this.virtualSize = m.get(JAVA_LONG, VSZ_OFFSET);
        this.residentSetSize = m.get(JAVA_LONG, RSS_OFFSET);
        this.kernelTime = m.get(JAVA_LONG, SYS_OFFSET) / 1_000_000L;
        this.userTime = m.get(JAVA_LONG, USR_OFFSET) / 1_000_000L;
        this.startTime = m.get(JAVA_LONG, START_TVSEC_OFFSET) * 1000L + m.get(JAVA_LONG, START_TVUSEC_OFFSET) / 1000L;
        this.upTime = now - this.startTime;
        this.openFiles = m.get(JAVA_INT, NFILES_OFFSET);
        this.bitness = (m.get(JAVA_INT, FLAGS_OFFSET) & P_LP64) == 0 ? 32 : 64;
        this.majorFaults = m.get(JAVA_INT, PGIN_OFFSET);
        // testing using getrusage confirms pti_faults includes both major and minor
        this.minorFaults = m.get(JAVA_INT, FAULTS_OFFSET) - this.majorFaults;
        this.contextSwitches = m.get(JAVA_INT, CSW_OFFSET);
        /*-
        if (this.majorVersion > 10 || this.minorVersion >= 9) {
            try (CloseableRUsageInfoV2 rUsageInfoV2 = new CloseableRUsageInfoV2()) {
                if (0 == SystemB.INSTANCE.proc_pid_rusage(getProcessID(), SystemB.RUSAGE_INFO_V2, rUsageInfoV2)) {
                    this.bytesRead = rUsageInfoV2.ri_diskio_bytesread;
                    this.bytesWritten = rUsageInfoV2.ri_diskio_byteswritten;
                }
            }
        }
        try (CloseableVnodePathInfo vpi = new CloseableVnodePathInfo()) {
            if (0 < SystemB.INSTANCE.proc_pidinfo(getProcessID(), SystemB.PROC_PIDVNODEPATHINFO, 0, vpi, vpi.size())) {
                this.currentWorkingDirectory = Native.toString(vpi.pvi_cdir.vip_path, StandardCharsets.US_ASCII);
            }
        }
        */
        return true;
    }
}
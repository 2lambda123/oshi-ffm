/*
 * Copyright 2022 the OSHI-FFM project contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package ooo.oshi.util.platform.mac;

import static java.lang.foreign.MemoryAddress.NULL;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static ooo.oshi.foreign.mac.SystemLibrary.INT_SIZE;
import static ooo.oshi.foreign.mac.SystemLibrary.LONG_SIZE;
import static ooo.oshi.foreign.mac.SystemLibrary.errno;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ooo.oshi.annotation.concurrent.ThreadSafe;
import ooo.oshi.foreign.mac.SystemLibrary;

/**
 * Provides access to sysctl calls on macOS
 */
@ThreadSafe
public final class SysctlUtil {

    private static final Logger LOG = LoggerFactory.getLogger(SysctlUtil.class);

    private static final String SYSCTL_FAIL = "Failed sysctl call: {}, Error code: {}";

    private SysctlUtil() {
    }

    /**
     * Executes a sysctl call with an int result
     *
     * @param name name of the sysctl
     * @param def  default int value
     * @return The int result of the call if successful; the default otherwise
     */
    public static int sysctl(String name, int def) {
        SegmentAllocator allocator = SegmentAllocator.implicitAllocator();
        MemorySegment cName = allocator.allocateUtf8String(name);
        MemorySegment size = allocator.allocate(JAVA_LONG, INT_SIZE);
        MemorySegment m = allocator.allocate(INT_SIZE);
        int res = SystemLibrary.sysctlbyname(cName, m, size, NULL, 0L);
        if (0 != res) {
            LOG.warn(SYSCTL_FAIL, name, errno());
            return def;
        }
        return m.get(JAVA_INT, 0);
    }

    /**
     * Executes a sysctl call with a long result
     *
     * @param name name of the sysctl
     * @param def  default long value
     * @return The long result of the call if successful; the default otherwise
     */
    public static long sysctl(String name, long def) {
        SegmentAllocator allocator = SegmentAllocator.implicitAllocator();
        MemorySegment cName = allocator.allocateUtf8String(name);
        MemorySegment size = allocator.allocate(JAVA_LONG, LONG_SIZE);
        MemorySegment m = allocator.allocate(LONG_SIZE);
        int res = SystemLibrary.sysctlbyname(cName, m, size, NULL, 0L);
        if (0 != res) {
            LOG.warn(SYSCTL_FAIL, name, errno());
            return def;
        }
        return m.get(JAVA_LONG, 0);
    }

    /**
     * Executes a sysctl call with a String result
     *
     * @param name name of the sysctl
     * @param def  default String value
     * @return The String result of the call if successful; the default otherwise
     */
    public static String sysctl(String name, String def) {
        SegmentAllocator allocator = SegmentAllocator.implicitAllocator();
        MemorySegment cName = allocator.allocateUtf8String(name);
        MemorySegment size = allocator.allocate(JAVA_LONG);
        // Call first time with null pointer to get value of size
        if (0 != SystemLibrary.sysctlbyname(cName, NULL, size, NULL, 0L)) {
            LOG.warn(SYSCTL_FAIL, name, errno());
            return def;
        }
        // Call again with proper allocation, add one for null terminator
        long sizeToAllocate = size.get(JAVA_LONG, 0) + 1;
        MemorySegment m = allocator.allocate(sizeToAllocate);
        if (0 != SystemLibrary.sysctlbyname(cName, m, size, NULL, 0L)) {
            // LOG.warn(SYSCTL_FAIL, name, Native.getLastError());
            return def;
        }
        return m.getUtf8String(0);
    }

    /**
     * Executes a sysctl call with a Pointer result
     *
     * @param name name of the sysctl
     * @return An allocated memory buffer containing the result on success, null otherwise. Its value on failure is
     *         undefined.
     */
    public static MemorySegment sysctl(String name) {
        SegmentAllocator allocator = SegmentAllocator.implicitAllocator();
        MemorySegment cName = allocator.allocateUtf8String(name);
        MemorySegment size = allocator.allocate(JAVA_LONG);
        // Call first time with null pointer to get value of size
        if (0 != SystemLibrary.sysctlbyname(cName, NULL, size, NULL, 0L)) {
            // LOG.warn(SYSCTL_FAIL, name, Native.getLastError());
            return null;
        }
        // Call again with proper allocation
        long sizeToAllocate = size.get(JAVA_LONG, 0);
        MemorySegment m = allocator.allocate(sizeToAllocate);
        if (0 != SystemLibrary.sysctlbyname(cName, m, size, NULL, 0L)) {
            LOG.warn(SYSCTL_FAIL, name, errno());
            return null;
        }
        return m;
    }

    /**
     * Executes a sysctl call with a Pointer result
     *
     * @param mib            a MIB array
     * @param sizeToAllocate the size of the buffer to allocate
     * @return An allocated memory buffer containing the result on success, null otherwise. Its value on failure is
     *         undefined.
     */
    public static MemorySegment sysctl(int[] mib, long sizeToAllocate) {
        SegmentAllocator allocator = SegmentAllocator.implicitAllocator();
        MemorySegment m = allocator.allocate(sizeToAllocate);
        MemorySegment size = allocator.allocate(JAVA_LONG, sizeToAllocate);
        MemorySegment cMib = allocator.allocateArray(JAVA_INT, mib);
        int res = SystemLibrary.sysctl(cMib, mib.length, m, size, NULL, 0L);
        if (0 != res) {
            LOG.warn(SYSCTL_FAIL, Arrays.toString(mib), errno());
            return null;
        }
        return m;
    }
}

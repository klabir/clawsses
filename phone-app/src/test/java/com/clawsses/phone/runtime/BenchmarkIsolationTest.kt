package com.clawsses.phone.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkIsolationTest {
    @Test
    fun acceptsOnlyDedicatedBenchmarkPackage() {
        assertTrue(BenchmarkIsolation.isBenchmarkPackage("com.clawsses.phone.benchmark"))
        assertFalse(BenchmarkIsolation.isBenchmarkPackage("com.clawsses.phone"))
        assertFalse(BenchmarkIsolation.isBenchmarkPackage("com.clawsses.phone.benchmark.preview"))
    }
}

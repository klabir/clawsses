package com.clawsses.phone.glasses

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HiRokidServiceConnectionAdapterTest {
    private open class Parent(private val runnable: Runnable)
    private class Child(runnable: Runnable) : Parent(runnable)

    @Test
    fun `finds assignable private field in superclass`() {
        val expected = Runnable { }
        assertSame(expected, findAssignableInstanceField(Child(expected), Runnable::class.java))
    }

    @Test
    fun `returns null when compatibility field is absent`() {
        assertNull(findAssignableInstanceField(Any(), Runnable::class.java))
    }
}

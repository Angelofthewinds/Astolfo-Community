package xyz.astolfo.astolfocommunity.utils

import org.junit.Test
import java.util.concurrent.TimeUnit

class CachedMapTests {

    @Test
    fun basicMapFunctions() {
        val map = CachedMap<Long, String>(5L, TimeUnit.SECONDS)

        assert(map.isEmpty())
        map[1] = "Hello"
        assert(map[1] == "Hello")
        assert(map.isNotEmpty())
        map.clear()
        assert(map[1] == null)
        assert(map.isEmpty())
        map[1] = "Hello"
        map[2] = "Hello World"
        assert(map.size == 2)
        assert(map[1] == "Hello")
        assert(map[2] == "Hello World")
        map.remove(1)
        assert(map.size == 1)
        assert(map[1] == null)
        assert(map[2] == "Hello World")
    }

    @Test
    fun expireTest() {
        val map = CachedMap<Long, String>(100, TimeUnit.MILLISECONDS)

        assert(map.isEmpty())
        map[1] = "Hello"
        assert(map.isNotEmpty())
        Thread.sleep(150)
        assert(map.isEmpty())

        map[1] = "Hello"
        assert(map.isNotEmpty())
        Thread.sleep(75)
        assert(map.isNotEmpty())
        assert(map[1] == "Hello")
        Thread.sleep(75)
        assert(map.isNotEmpty())
        Thread.sleep(75)
        assert(map.isEmpty())
    }

    @Test
    fun testCallback() {
        var called = false
        val map = CachedMap<Long, String>(100, TimeUnit.MILLISECONDS) { key, value ->
            assert(key == 1L)
            assert(value == "Hello")
            called = true
        }

        assert(!called)
        map[1] = "Hello"
        Thread.sleep(75)
        assert(!called)
        Thread.sleep(75)
        assert(called)
    }

}
package com.felix.faze

import java.io.IOException
import java.net.ServerSocket
import org.junit.Assert.assertTrue
import org.junit.Test

class AbortTest {
    /** A server that accepts and then says nothing, like a model that is still thinking. Stop must end the wait at once. */
    @Test fun abortAllEndsARequestThatIsWaitingOnTheNetwork() {
        val server = ServerSocket(0)
        val accepted = ArrayList<java.net.Socket>()
        Thread { try { accepted.add(server.accept()) } catch (e: Exception) { } }.start()
        val t = UrlTransport()
        var failure: Exception? = null
        var ended = false
        val worker = Thread {
            try { t.postStream("http://127.0.0.1:${server.localPort}/x", emptyMap(), "{}", 60000) { true } }
            catch (e: Exception) { failure = e }
            ended = true
        }
        worker.start()
        Thread.sleep(400)
        assertTrue("still waiting before the abort", !ended)
        val began = System.currentTimeMillis()
        t.abortAll()
        worker.join(3000)
        assertTrue("ended after the abort", ended)
        assertTrue("ended quickly", System.currentTimeMillis() - began < 2500)
        assertTrue(failure is IOException)
        server.close()
        accepted.forEach { it.close() }
    }
}

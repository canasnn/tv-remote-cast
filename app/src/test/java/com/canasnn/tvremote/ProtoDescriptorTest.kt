package com.canasnn.tvremote

import com.canasnn.tvremote.androidLib.pairing.Pairingmessage
import com.canasnn.tvremote.androidLib.remote.Remotemessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The generated protocol classes must load and know their own message types. */
class ProtoDescriptorTest {
    @Test
    fun remoteDescriptorLoads() {
        val d = Remotemessage.getDescriptor()
        assertNotNull(d.findMessageTypeByName("RemoteImeBatchEdit"))
        val msg = Remotemessage.RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(Remotemessage.RemoteImeBatchEdit.newBuilder().setImeCounter(3).setFieldCounter(4))
            .build()
        val parsed = Remotemessage.RemoteMessage.parseFrom(msg.toByteArray())
        assertEquals(3, parsed.remoteImeBatchEdit.imeCounter)
        println(d.`package` + " / " + parsed)
    }

    @Test
    fun pairingDescriptorLoads() {
        assertNotNull(Pairingmessage.getDescriptor().findMessageTypeByName("PairingMessage"))
    }
}

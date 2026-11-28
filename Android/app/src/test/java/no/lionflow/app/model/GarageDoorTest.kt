package no.lionflow.app.model

import no.lionflow.app.door.DoorException
import no.lionflow.app.door.DoorState
import no.lionflow.app.door.GateApi
import no.lionflow.app.door.HomeAssistantApi
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The garage door openers: the values were worked out separately in Python from the ismartgate protocol. */
class GarageDoorTest {
    private val user = "Admin"
    private val pw = "pässword1"

    @Test fun ismartgateKeyAndToken() {
        assertEquals("3fbfa5e9!ab0*#15", GateApi.isgKey(user, pw))
        assertEquals("f7001ecfe4d09ea0e58cb09058ba11ffe3ea36f0", GateApi.token(user))
    }

    @Test fun commandIsWrittenLikePython() {
        assertEquals("""["Admin", "pässword1", "info", "", ""]""", GateApi.command(user, pw, "info", "", ""))
        assertEquals("""["a\"b\\c", "x"]""", GateApi.command("a\"b\\c", "x"))
    }

    @Test fun encryptsLikeTheOpenerWants() {
        val key = GateApi.isgKey(user, pw)
        assertEquals(
            "0123456789abcdefAXijxLCBNVQv+B6peOlRyoThmnf6JeiNs06HQdBXahJIj9zdCM16NHpW6Io7Kzaz",
            GateApi.encrypt(key, GateApi.command(user, pw, "info", "", ""), "0123456789abcdef"),
        )
        assertEquals(
            "abcdefabcdefabcdfX4NLOp0nkR3/q1V2KL7RJw3qIfUoZ/i0V5jtma4F50Gi7J4N2c0zQpc1v58S2qj",
            GateApi.encrypt(GateApi.GOGOGATE_KEY, GateApi.command("admin", "pw", "activate", "1", "999"), "abcdefabcdefabcd"),
        )
    }

    @Test fun readsTheDoorsFromTheAnswer() {
        val key = GateApi.isgKey(user, pw)
        val answer = "fedcba9876543210zQKDEzU1uxn3HOmwm1QOtAz9vlJt8eOR8A3sfxAYcQGknwmu4f9SnqrmGJBfeNcrfD9iocfYc6gHKEHFT36nGFcAGCPWg+92eJv/aaBArfiFAsRzQpo9yc/1sxr759ZKXJtRN76T/ESl7zVfUUR3lghLLkh5VslyFZ706cDjq2poe+WKKRehz1oJMCbCW4IYdxYa0TyvehYDQQ9oKoyVlteSPja7MvqgAGhdCyCN6RCqGIO/7qqdP3Z7BmXARIfl5dHe6HFsGxfJQmZ94YQrGIlAW7vPirdNqicQ/lxaJX0ZNwJFpT7sou2xVb69mAS4YlaNl4Mxe/vfAuLHjlHE3qhK4VsnPNaVW5+M13k0b2Zgee52gZCpQk4CFYzKazYY+gtbxTRdzM7xCan+WliQbEJAaqVXtN4Hg1B6yXJPYTSrWUYyO80jYSQVEPlaV49cTlT+mtgq0l0NEZJCVeisOJiD1YcKzxc8Hco8MofHI6Tbe94otL4DZorVv+KxWtQ/XdbpN56MxhfCA1h1ia+yb3XRmUJ4EH6MBON3EIUQaNKIKWtlkZeOe8xVHTqHeHAK"
        val xml = GateApi.decrypt(key, answer)
        assertTrue(xml.startsWith("<?xml"))
        val doors = GateApi.parseDoors(xml, ismartgate = true)
        assertEquals(listOf("1", "2"), doors.map { it.first.id })   // door 3 is switched off
        assertEquals("Garasje", doors[0].first.name)
        assertEquals(DoorState.CLOSED, doors[0].first.state)
        assertEquals(DoorState.OPEN, doors[1].first.state)
        assertEquals("abc123", doors[0].second)
    }

    @Test fun roundTrip() {
        val key = GateApi.isgKey("ola", "hemmelig")
        val text = "<response><result>OK</result></response>"
        assertEquals(text, GateApi.decrypt(key, GateApi.encrypt(key, text)))
    }

    @Test fun gogogateUsesTheSharedCode() {
        val xml = "<response><apicode>777</apicode><door1><name>Garage</name><status>opened</status></door1><door2><name></name><status>undefined</status></door2><door3><name></name></door3></response>"
        val doors = GateApi.parseDoors(xml, ismartgate = false)
        assertEquals(1, doors.size)
        assertEquals("777", doors[0].second)
        assertEquals(DoorState.OPEN, doors[0].first.state)
    }

    @Test fun errorsAreReadable() {
        val e = assertThrows(DoorException::class.java) { GateApi.checkError("<response><error><errorcode>11</errorcode><errormsg>Code: 11 - bad</errormsg></error></response>") }
        assertEquals("Wrong username or password", e.message)
        GateApi.checkError("<response><result>OK</result></response>")
    }

    @Test fun addresses() {
        assertEquals(listOf("https://A1B2C3.isgaccess.com", "http://A1B2C3.isgaccess.com"), GateApi.bases(" A1B2C3 ", true))
        assertEquals(listOf("http://192.168.1.40"), GateApi.bases("192.168.1.40", true))
        assertEquals(listOf("https://a1b2.isgaccess.com"), GateApi.bases("https://a1b2.isgaccess.com/index.php", true))
        assertEquals(listOf("https://gate.example.com", "http://gate.example.com"), GateApi.bases("gate.example.com", true))
        assertEquals(listOf("http://10.0.0.5:8080"), GateApi.bases("10.0.0.5:8080", false))
    }

    @Test fun homeAssistantCoversGarageFirst() {
        val arr = JSONArray("""[
            {"entity_id":"light.kitchen","state":"on","attributes":{}},
            {"entity_id":"cover.blinds","state":"open","attributes":{"friendly_name":"Blinds","device_class":"blind"}},
            {"entity_id":"cover.garage","state":"closing","attributes":{"friendly_name":"Garasje","device_class":"garage"}}
        ]""")
        val covers = HomeAssistantApi.parseCovers(arr)
        assertEquals(listOf("cover.garage", "cover.blinds"), covers.map { it.id })
        assertEquals(DoorState.CLOSING, covers[0].state)
        assertEquals("Garasje", covers[0].name)
    }
}

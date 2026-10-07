package com.sticky.reminder

import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRemoteTest {
    @Test
    fun multipartGovdesiBicimiDogru() {
        val body = multipartBody("B1", """{"version":1,"tasks":[]}""")
        assertTrue(
            body.startsWith(
                "--B1\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"sticky.json\"",
            ),
        )
        assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(
            body.contains(
                "\r\n--B1\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                    "{\"version\":1,\"tasks\":[]}\r\n--B1--",
            ),
        )
        assertTrue(body.endsWith("--B1--"))
    }
}

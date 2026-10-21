package com.ruterika.castcore.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRedirectRulesTest {

    @Test
    fun parsesAndMatches() {
        val rules = AdRedirectRules.parse(
            """
            # comment
            path::contains::/click;;qp::setContainsAnyIgnoreCase::[zoneid,campaignid]

            host::matchesRegex::^ak\.;;qp::setContainsAll::[rb,pl]
            url::contains::/popunder/
            qp::startsWith::utm_;;host::endsWith::ads.example
            not-a-rule
            host::nope::x
            path::matchesRegex::([
            qp::setContainsAll::[]
            """.trimIndent()
        )
        assertEquals(4, rules.size)
        assertTrue(rules.matches("https://track.example/click?ZoneId=5"))
        assertFalse(rules.matches("https://track.example/click?page=5"))
        assertFalse(rules.matches("https://track.example/home?zoneid=5"))
        assertTrue(rules.matches("https://ak.example.com/x?rb=1&pl=2&z=3"))
        assertFalse(rules.matches("https://ak.example.com/x?RB=1&pl=2"))
        assertTrue(rules.matches("https://a.example/popunder/1"))
        assertTrue(rules.matches("https://x.ads.example/?utm_source=a"))
        assertFalse(rules.matches("https://x.ads.example/?source=a"))
        assertFalse(rules.matches("intent://x#Intent;end"))
    }

    @Test
    fun validation() {
        assertTrue(AdRedirectRules.isValidRule("path::contains::/r/dir"))
        assertFalse(AdRedirectRules.isValidRule("# path::contains::/r"))
        assertFalse(AdRedirectRules.isValidRule("host::setContainsAll::[a]"))
        assertFalse(AdRedirectRules.isValidRule("path::contains::"))
        assertFalse(AdRedirectRules.EMPTY.matches("https://a.example/click?zoneid=1"))
    }

    @Test
    fun embeddedRulesCatchCommonAdRedirects() {
        val rules = SiteRules.default()
        assertTrue(rules.isAdRedirect("https://go.adnet.example/click?zoneid=123&cost=1"))
        assertTrue(rules.isAdRedirect("https://x.example/afu.php?zoneid=42&var=1"))
        assertFalse(rules.isAdRedirect("https://www.example.com/watch?v=1"))
        assertFalse(rules.isAdRedirect("https://shop.example/click-and-collect"))
    }
}

package com.carlink.ocbm

import java.util.Base64

/**
 * Update packages as enigy/ocbm builds them. [PACKAGE_1_1_0] is the output of tools/make_ocbm_update.sh at eec9db5,
 * run as tools/ocbm_update_test.sh runs it (stand-in binaries, only ocbmd changed): `ocbm-update-1.1.0.tgz`, whose
 * VERSION is "1.1.0 ocbm eec9db5", FROM_MIN "1.0.0" and ROOM "need_kb=387" and "trial=yes".
 */
object UpdateFixtures {
    val PACKAGE_1_1_0: ByteArray =
        Base64.getDecoder().decode(
            listOf(
                "H4sIAAAAAAACA+1YzW4cNwz2eZ/CL1BblERKOuTQQ4vm4BiI0V4NUqTqbV2vsbNOkbcvZ13U9hZI0EM2KTy8LNae/Yb6fijN/PLD",
                "+6u3l+9OvmQFL8p5/+l1+BlCTCeAsYSCJQKeBIiQ4slpODlCPUw73nor281m96nrPvf/w8X9TwrO4Cycbrr8cWrWmwquTpZ6PfXj",
                "+8uL64u3775q/gMd5j9iXvJ/nPyHs7BE/tXW1U/fR6Srny+uvlr+U4JymP9UaMn/MarHVkOsQyhDrayoqiWHlqIQhzZKZgm5pqy9",
                "cteaq5U+pGmpTKLj9HTq2/X97nw+QlyLk3Q23aySdhpWQ6GCbK5+0Q7QxEKqYE0bYQzU6uhRFNpoxZA5GLfeErT2ElXX0z3v+s2M",
                "bKN7SwGGQi25UogUkwUpHTMlbzeHPqLEkv0o01tgwjSkRAk1QOL+Enms79bTI25oUHWYcs04WsKRYuzuzTJiFUHQlpIUxOT39IWD",
                "XxCs9iZ+zzgOcLf2wbZ7JlphSoIkvr40AmONQKN3UOCsZSQeRBZHViBKKhC6ppaJC1bxJb7Enex27Gx65DiiYgg8uM4EFiLKjfJo",
                "UdkSQObOvaDTTCBKyE3SoFHnPzdM9BL54V55ZzNu79W4xpqT9bmNTpBDBW+quFmcXck51WKZCwg3qsI5O/eVTGH0J+3ubzZ35lRM",
                "tu83OHClas0il2xNtAsMzuLaQfazJ4ALFwYbU06lkXJxkgyMZqntCXe7+c36br258673HPcAVP3XJaUYAMUhc4SeR1FphgWjaXSP",
                "aBYrqTZfVBhOcwk5uQX/Qd6yrjfXfP83rttf1FpiV1/ROhduQ6uE4j+PShAChCTdmcDC7utO2ePTay+SiowDXLWdtz0Du81dXg0e",
                "iKzVkTiRZrY8PFattGBSXSjym1XgOY8cRsHWzYOlHQ+Ab/h2Ri2MvUtrnN1eET2miCVpctWY3MSMkht4GsHNEGYLxm6WOGLKxvlJ",
                "uO3D3fVsCn1ELaG7fZ2wGFDJWZWRzIXpBcCfGRCbRMXMkKWhmDpRtXBCE3aVX6BOD/e2/bCeNtsZGmrI2HXgyEZs6P5vNqDl4NQW",
                "jubCuZJteOJ6Fo2p11J8t0AuFeWp4cmmabbDS3i3qjftwoG0ZNF95HOMx0And/SM8/gR58MD4WzEVF3TGBNItOhp8kg/TNtzWd+d",
                "r90S282tXU9/rn0OrdDz0H0UNh7dfWeko1iioDB/DbmZty5OFCGGDA0JQ2OZFa3+lR6Rpxma+TvZrvVXW3UE6e43UCzkQQvmSjd2",
                "wX34+sqTe0CqmriqsarTEHVABh90rYg8B5Wdrqjl4al1pmpKHgI0qqQxUwlxHi4+7FokVM7ROY2SfM1QOuEAA8/iM7jO2/tb/qir",
                "OUwxEiF4g82zDsqpVRNtJeXqW0X2iPlcGuTTCwUbdYruZ2JvkcszTKc06spF9V2gso9W1WTu0DK6jVF9JqYucT9jkfyizu7DSKUp",
                "d+AWg8cF4jPAvWGXg+x/rfeXlxdf+h6ff/9z+PwHSMvz31Hqzkyvf5c3vqOvdts13775aNMSo1dTY31r0/mXz7+fnD/x/if86/1v",
                "gZNTXPJ/JP3nXfRb0j/5nrDof1z996eob0X/knDR/yvovz9FH11/gGf6+zkRcknL+7+j1F7w0w9xOfIttdRSSy211FJLvY76Cy9g",
                "Mr0AKAAA",
            ).joinToString(""),
        )

    /** Its SHA-256, from the builder's .sha256 beside it. */
    const val PACKAGE_1_1_0_SHA256 = "fa4a4a42ee546805b3f208f6fe609ad32f9480c1bf1c22356bc3c37cf2897bdd"
}

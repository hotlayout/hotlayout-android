package com.hotlayout


/**
 * Which image addresses the reader may load, and how much of one. The host decides; by default only `https:`
 * addresses of a named host (see [httpsOnly]) and at most [maxDownloadBytes] a picture.
 */
public class ImagePolicy(
    /** Addresses of the host's asset tokens (`logo`, `banner`, `placeholder`). */
    public val assets: Map<String, String> = emptyMap(),
    /** The most bytes the reader downloads for one picture; a larger one is drawn as the placeholder. */
    public val maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    /**
     * The longest side, in pixels, a picture is decoded at: a bigger file is sampled down to it, the ratio kept.
     */
    public val maxPixelSide: Int = DEFAULT_MAX_PIXEL_SIDE,
    /** The most time, in milliseconds, one picture may take from the request to the last byte; past it the transfer is cut. */
    public val totalDeadlineMs: Long = DEFAULT_TOTAL_DEADLINE_MS,
    /**
     * The host's transport: gets the address (already through [resolve]) and the byte cap and returns the bytes, or
     * `null` for a failure. With it the reader makes no request of its own, so the host can use a client with its
     * own cookie jar, proxy and certificates. It must honour the cap and its own timeouts; the reader still holds
     * the whole call to [totalDeadlineMs] and refuses more than the cap.
     */
    public val fetch: (suspend (address: String, maxBytes: Long) -> ByteArray?)? = null,
    /** Turns an address of the document into one to load, or `null` to draw the placeholder. */
    public val resolve: (String) -> String? = ::httpsOnly,
) {
    public companion object {
        /** The default cap on one download: 10 MB. */
        public const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 10L * 1024 * 1024

        /** The default longest side of a decoded picture. */
        public const val DEFAULT_MAX_PIXEL_SIDE: Int = 4096

        /** The default deadline of one whole download: 30 s. */
        public const val DEFAULT_TOTAL_DEADLINE_MS: Long = 30_000

        /**
         * The default rule: exactly the address form of the action `openUrl` (`x-actions.forms.httpsUrl`): `https` and
         * a host with a letter-only top-level domain. A host of digits (`127.0.0.1`, `2130706433`, `0x7f.1`), `localhost`, a bare name and an
         * address in brackets make the phone connect to itself or to its network, so they get the placeholder.
         */
        public fun httpsOnly(address: String): String? =
            address.takeIf { com.hotlayout.actions.ActionCatalog.formProblem("httpsUrl", it) == null }
    }
}

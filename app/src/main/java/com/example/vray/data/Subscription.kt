package com.example.vray.data

/**
 * One subscription source (a link the seller gave the customer). Every server
 * pulled in from this link is tagged with this subscription's id, so refreshing
 * or deleting a subscription only touches its own servers — not manually added
 * ones, and not servers from a different subscription link.
 */
data class Subscription(
    val id: String,
    val label: String,
    val url: String,
    val lastUpdatedEpochMs: Long = 0L,
    val expireAtEpochSec: Long? = null,
    val dataLimitBytes: Long? = null,
    val dataUsedBytes: Long? = null
)

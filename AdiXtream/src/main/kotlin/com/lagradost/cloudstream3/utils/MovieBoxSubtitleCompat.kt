package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile

/** MovieBox-only compatibility shim for the CloudStream API used by this repo. */
fun newSubtitleFile(name: String, url: String): SubtitleFile = SubtitleFile(name, url)

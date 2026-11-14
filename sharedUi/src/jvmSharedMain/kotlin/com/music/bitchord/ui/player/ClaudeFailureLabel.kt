package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/** A few words on why Claude gave nothing usable — the lyric status line and the diagnostics log. */
@Composable
fun claudeFailureLabel(failure: ClaudeFailure?): String = stringResource(
    when (failure) {
        ClaudeFailure.COOLDOWN -> Res.string.claude_fail_cooldown
        ClaudeFailure.KEY_REJECTED -> Res.string.claude_fail_key_rejected
        ClaudeFailure.NO_CREDIT -> Res.string.claude_fail_no_credit
        ClaudeFailure.RATE_LIMIT -> Res.string.claude_fail_rate_limit
        ClaudeFailure.OVERLOADED -> Res.string.claude_fail_overloaded
        ClaudeFailure.SERVER_ERROR -> Res.string.claude_fail_server_error
        ClaudeFailure.BAD_REQUEST -> Res.string.claude_fail_bad_request
        ClaudeFailure.TIMEOUT -> Res.string.claude_fail_timeout
        ClaudeFailure.NETWORK -> Res.string.claude_fail_network
        ClaudeFailure.REFUSAL -> Res.string.claude_fail_refusal
        ClaudeFailure.CUT_OFF -> Res.string.claude_fail_cut_off
        ClaudeFailure.LINE_MISMATCH -> Res.string.claude_fail_line_mismatch
        ClaudeFailure.BAD_ANSWER -> Res.string.claude_fail_bad_answer
        ClaudeFailure.ERROR, null -> Res.string.claude_fail_error
    },
)

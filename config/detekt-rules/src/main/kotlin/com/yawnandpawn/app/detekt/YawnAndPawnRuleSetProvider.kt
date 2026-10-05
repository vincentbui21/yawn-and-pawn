package com.yawnandpawn.app.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** Project-specific detekt rules. Rule set id `yawn-and-pawn` in `config/detekt/detekt.yml`. */
class YawnAndPawnRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("yawn-and-pawn")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("NoPrintlnInCore") to ::NoPrintlnInCore,
                RuleName("NoDirectTimeAccess") to ::NoDirectTimeAccess,
                RuleName("NoRawColor") to ::NoRawColor,
                RuleName("NoRawCornerRadius") to ::NoRawCornerRadius,
                RuleName("NoRawSp") to ::NoRawSp,
                RuleName("NoInexactAlarm") to ::NoInexactAlarm,
                RuleName("CredentialStorageAccess") to ::CredentialStorageAccess,
                RuleName("NoAudioCaptureOrRouting") to ::NoAudioCaptureOrRouting,
            ),
        )
}

package com.yawnandpawn.app.playcatalog

/** `dry-run` prints the plan only; `apply` also writes it. */
enum class Mode(
    val argument: String,
) {
    DRY_RUN("dry-run"),
    APPLY("apply"),
    ;

    companion object {
        fun parse(value: String?): Mode? = entries.firstOrNull { it.argument == value }
    }
}

/** Process exit codes. */
object ExitCode {
    const val OK = 0
    const val API_FAILURE = 1
    const val USAGE = 2
}

/**
 * Builds the desired catalogue, compares it with Play and, in [Mode.APPLY], writes the difference. The first failed
 * call stops the run (exit [ExitCode.API_FAILURE]) with the API message; nothing is retried, so the next dry run shows
 * what is left.
 */
class CatalogRunner(
    private val api: PlayCatalogApi,
    private val out: (String) -> Unit,
    private val packageName: String = SnoozeCatalog.PACKAGE_NAME,
) {
    /** "i of n" while applying, for the failure message. */
    private var progress: String? = null

    fun run(mode: Mode): Int =
        try {
            execute(mode)
        } catch (e: PlayApiException) {
            report(e)
            ExitCode.API_FAILURE
        }

    private fun execute(mode: Mode): Int {
        out("Play catalogue for $packageName (mode: ${mode.argument})")
        val existing = api.listOneTimeProducts(packageName)
        out("Found ${existing.size} one-time products in Play.")
        val conversions =
            (1..SnoozeCatalog.PRODUCT_COUNT).associateWith { tier ->
                api.convertRegionPrices(packageName, SnoozeCatalog.basePrice(tier))
            }
        val regions = conversions.getValue(1)
        out("Play prices ${regions.regions.size} regions (regions version ${regions.regionsVersion}).")
        val plan = CatalogPlanner.plan(existing, conversions)
        plan.products.forEach { out(line(it)) }
        out(plan.summary())
        if (plan.count(PlanKind.ATTENTION) > 0) {
            out("Products marked attention are left unchanged; fix them in Play Console first.")
        }
        when {
            plan.changes.isEmpty() -> out("0 changes. Play matches the catalogue.")
            mode == Mode.DRY_RUN -> out("Dry run: nothing was written. Run ./gradlew playCatalog -Pmode=apply to make these changes.")
            else -> apply(plan)
        }
        return ExitCode.OK
    }

    private fun apply(plan: CatalogPlan) {
        val changes = plan.changes
        changes.forEachIndexed { index, change ->
            progress = "${index + 1} of ${changes.size}"
            var stored: OneTimeProduct? = null
            if (change.patch.isNotEmpty()) {
                stored =
                    api.patchOneTimeProduct(
                        packageName = packageName,
                        product = requireNotNull(change.desired),
                        fields = change.patch,
                        regionsVersion = requireNotNull(change.regionsVersion),
                        allowMissing = change.kind == PlanKind.CREATE,
                    )
            }
            val buyState = stored?.purchaseOptions?.firstOrNull { it.id == SnoozeCatalog.PURCHASE_OPTION_ID }?.state
            if (change.activate && buyState != OptionState.ACTIVE) {
                api.activatePurchaseOption(packageName, change.productId, SnoozeCatalog.PURCHASE_OPTION_ID)
            }
            out("Done $progress: ${change.productId} ${change.kind.label}")
        }
        progress = null
        out("Applied ${changes.size} changes. Run the dry run again: it should report 0 changes.")
    }

    private fun report(e: PlayApiException) {
        val where = progress?.let { " while applying change $it" }.orEmpty()
        out("FAILED$where: ${e.call}: ${e.message}")
        if (e.isPermissionProblem) {
            out(
                "The service account needs the Play Console permission \"Manage store presence\" (and \"View app information\") " +
                    "for Yawn & Pawn: Play Console > Users and permissions.",
            )
        }
        out("Stopped. Nothing is retried; run the dry run to see what is left.")
    }

    private fun line(plan: ProductPlan): String {
        val reasons = plan.reasons.joinToString("; ")
        return (plan.productId.padEnd(ID_WIDTH) + plan.kind.label.padEnd(KIND_WIDTH) + reasons).trimEnd()
    }

    private companion object {
        const val ID_WIDTH = 16
        const val KIND_WIDTH = 11
    }
}

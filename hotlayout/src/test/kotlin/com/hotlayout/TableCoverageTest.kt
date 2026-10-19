package com.hotlayout

import com.hotlayout.support.Contract
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every group of every shared case table is either RUN by a test of this reader, or named here as
 * belonging to a later wave, or as a verdict of the builder's validator (which a reader does not
 * have). A new group in the contract fails this test until somebody decides which it is: a group
 * is never silently ignored.
 */
class TableCoverageTest {
    companion object {
        /** Groups run by the tests of the foundation, as `<table folder>/<group>`. */
        val run: Set<String> = setOf(
            "template/cases", "template/shownTextCases", "template/shownTemplateCases", "template/moneyCases",
            "template/datePartsCases", "template/countdownCases", "template/compactCases",
            "locale/cases",
            "actions/formCases", "actions/handedCases", "actions/flowCases", "actions/hapticCases",
            "scope/readCases", "scope/recordCases", "scope/flowCases", "scope/entitySourceCases", "scope/liveRecordCases", "scope/liveFlowCases",
            "inputs/emptinessCases", "inputs/valuePathCases",
            "repeater-filter/filterCases", "repeater-filter/limitCases", "repeater-filter/emptinessCases",
            "icon-text/orderCases", "icon-text/glyphCases", "icon-text/buttonSizeCases", "icon-text/iconButtonCases",
                        // Controls: press, long press, feedback, write actions, choice, swipe, paint.
            "composition/pressableCases", "composition/pressCases", "composition/nameCases",
            "press-feedback/kindCases", "press-feedback/poseCases", "press-feedback/ruleCases",
            "shadow-color/middleStopCases", "shadow-color/tintCases", "shadow-color/ruleCases",
            "border-dash/lineCases", "border-dash/pathCases", "border-dash/styleCases", "border-dash/ruleCases",
            "actions/writeCases", "actions/writeControlCases", "actions/writePressCases",
            "long-press/gestureCases", "long-press/keyCases", "long-press/nodeCases", "long-press/receiverCases",
            "long-press/ruleCases", "long-press/keyRepeatCases", "long-press/clickAfterFireCases", "long-press/onceRuleCases",
            "swipe-confirm/geometryCases", "swipe-confirm/naturalWidthCases", "swipe-confirm/progressCases",
            "swipe-confirm/releaseCases", "swipe-confirm/thumbCases", "swipe-confirm/captureCases", "swipe-confirm/pressCases",
            "swipe-confirm/ruleCases", "swipe-confirm/busyReleaseCases", "swipe-confirm/busyEndCases", "swipe-confirm/busyRuleCases",
            "inputs/choiceCases", "inputs/choiceSessionCases", "inputs/choicePlaceCases", "inputs/choiceControlCases", "inputs/writeFormCases",
            "inputs/filledCases", "inputs/formCases", "inputs/pressCases", "inputs/disabledLookCases",
            "icon-text/nameCases", "icon-text/iconPathCases", "icon-text/colorCases",
            "list-tile/lineCases", "list-tile/tileCases", "list-tile/leadingBoxCases", "list-tile/trailingColumnCases",
            "list-tile/rowCases", "list-tile/lookCases", "list-tile/leadingColorCases",
            "repeater-filter/gridCases", "repeater-filter/numberCases",
            "repeater-group/sameCases", "repeater-group/groupCases", "repeater-group/listCases", "repeater-group/ruleCases",
            "offset/layerCases", "offset/layerReadCases", "offset/layerPlaceCases",
            "rich-text/cases", "rich-text/struts", "rich-text/emphasis", "rich-text/emphasisStruts", "rich-text/amountPartCases",
            "rich-text/amountPartStruts", "rich-text/textGradientCases", "rich-text/textGradientPartCases",
            "list-marker/geometryCases", "list-marker/textCases", "list-marker/numberCases", "list-marker/instanceCases",
            "list-marker/layoutCases", "list-marker/naturalCases",
            "value-transition/curveCases", "value-transition/stepCases", "value-transition/digitsCases", "value-transition/decisionCases",
            "value-transition/targetCases", "value-transition/appearStepCases", "value-transition/appearDecisionCases",
            "value-transition/appearTargetCases", "value-transition/appearFlowCases",
            "align/readCases", "align/heightSourceCases", "align/shiftCases", "align/boxCases", "align/screenCases", "align/ruleCases",
            "offset/pullCases", "offset/pullReadCases",
            "progress/ratioCases", "progress/ringCases", "progress/arcCases", "progress/barCases", "progress/paintCases", "progress/segmentCases", "progress/segmentPlaceCases", "progress/segmentPaintCases", "progress/segmentGradientCases", "progress/ringLabelCases", "progress/ringLabelNoneCases", "progress/capCases", "progress/segmentFillCases", "progress/segmentFillInlineCases", "progress/transitionShareCases", "progress/glowStepCases", "progress/glowRuleCases",
            "motion/poseCases", "motion/glassCases",
        )

        /** The wave a whole table belongs to (groups of it that the foundation runs are in [run]). */
        val tableWave: Map<String, String> = mapOf(
            "align" to "wave 1 · alignment",
            "list-tile" to "wave 1 · lists",
            "repeater-filter" to "wave 1 · lists",
            "repeater-group" to "wave 1 · lists",
            "offset" to "wave 1 · stack",
            "progress" to "wave 1 · progress",
            "composition" to "wave 1 · controls",
            "press-feedback" to "wave 1 · controls",
            "shadow-color" to "wave 1 · controls",
            "border-dash" to "wave 1 · controls",
            "icon-text" to "wave 1 · lists",
            "actions" to "wave 1 · controls",
            "inputs" to "wave 2 · inputs & form",
            "swipe-confirm" to "wave 2 · gestures & motion",
            "long-press" to "wave 2 · gestures & motion",
            "motion" to "wave 2 · gestures & motion",
            "bottom-zone" to "wave 2 · screen anatomy",
            "top-zone" to "wave 2 · screen anatomy",
            "media-chrome" to "wave 2 · screen anatomy",
            "sheet-dismiss" to "wave 2 · modals",
            "carousel" to "wave 2 · carousel",
            "adaptive" to "wave 3 · adaptive",
        )

        /** Groups of a table that belong to another wave than the table. */
        val groupWave: Map<String, String> = mapOf(
            "media-chrome/ratioCases" to "wave 3 · images", "media-chrome/imageBoxCases" to "wave 3 · images",
            "media-chrome/paintCases" to "wave 3 · images", "media-chrome/radiusCases" to "wave 3 · images",
            "media-chrome/circleCases" to "wave 3 · images",
            "media-chrome/modalCases" to "wave 2 · modals", "media-chrome/modalAreaCases" to "wave 2 · modals",
            "media-chrome/placementCases" to "wave 2 · modals",
            "inputs/choiceCases" to "wave 1 · controls", "inputs/choiceSessionCases" to "wave 1 · controls",
            "inputs/choicePlaceCases" to "wave 1 · controls", "inputs/choiceControlCases" to "wave 1 · controls",
            "repeater-filter/carouselCases" to "wave 2 · carousel",
            "offset/pullCases" to "wave 1 · alignment (pull)", "offset/pullReadCases" to "wave 1 · alignment (pull)",
            "motion/blurCases" to "wave 2 · modals", "motion/glassCases" to "wave 2 · modals",
        )

        /**
         * The builder's validator decides these; a reader draws whatever document it is given. Other
         * `…RuleCases` groups often hold the reader's rules too: their wave decides.
         */
        fun isValidatorGroup(table: String, group: String): Boolean =
            group == "documentCases" || group == "schemaCases" || group.endsWith("DocumentCases") || group.endsWith("SchemaCases") ||
                group == "validityCases" || "$table/$group" in setOf(
                "inputs/ruleCases", "inputs/formRuleCases",
                // Where a node may stand and how deep a document may go: what the builder may publish.
                "composition/nestingCases", "composition/depthCases", "actions/writeTreeCases",
            )

        fun tables(): Map<String, Set<String>> {
            val root = java.io.File(Contract::class.java.getResource("/contract/fixtures")!!.toURI())
            return root.listFiles().orEmpty().filter { it.isDirectory && it.name != "themes" }.flatMap { folder ->
                folder.listFiles().orEmpty().filter { it.name.endsWith("-cases.json") }.map { folder.name to (Contract.groups(Contract.json("fixtures/${folder.name}/${it.name}")) - "records") }
            }.toMap()
        }

        fun status(table: String, group: String): String? = when {
            "$table/$group" in run -> "run"
            isValidatorGroup(table, group) -> "validator"
            groupWave["$table/$group"] != null -> groupWave["$table/$group"]
            else -> tableWave[table]
        }
    }

    @Test fun everyGroupIsRunOrNamed() {
        val unnamed = tables().flatMap { (table, groups) -> groups.filter { status(table, it) == null }.map { "$table/$it" } }
        assertTrue("groups nobody decided about: $unnamed", unnamed.isEmpty())
    }

    @Test fun everyRunGroupExists() {
        val present = tables().flatMap { (table, groups) -> groups.map { "$table/$it" } }.toSet()
        assertTrue("run groups the tables do not have: ${run - present}", (run - present).isEmpty())
    }

    /** The list of deferred groups, printed for the record of the waves. */
    @Test fun printDeferred() {
        val byWave = sortedMapOf<String, MutableList<String>>()
        for ((table, groups) in tables().toSortedMap()) for (group in groups.sorted()) {
            val status = status(table, group) ?: continue
            if (status != "run") byWave.getOrPut(status) { ArrayList() }.add("$table/$group")
        }
        for ((wave, groups) in byWave) println("$wave (${groups.size}): ${groups.joinToString()}")
    }
}

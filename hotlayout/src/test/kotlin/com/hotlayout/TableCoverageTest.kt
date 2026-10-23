package com.hotlayout

import com.hotlayout.support.Contract
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every group of every shared case table is either RUN by a test of this reader, or named here as
 * a verdict of the builder's validator (which a reader does not have). A new group in the contract
 * fails this test until somebody decides which it is: a group is never silently ignored.
 */
class TableCoverageTest {
    companion object {
        /** Groups run by the tests of this reader, as `<table folder>/<group>`. */
        val run: Set<String> = setOf(
            "template/cases", "template/shownTextCases", "template/shownTemplateCases", "template/moneyCases",
            "template/datePartsCases", "template/countdownCases", "template/compactCases",
            "locale/cases",
            "actions/formCases", "actions/handedCases", "actions/flowCases", "actions/hapticCases",
            "scope/readCases", "scope/recordCases", "scope/flowCases", "scope/entitySourceCases", "scope/liveRecordCases", "scope/liveFlowCases",
            "inputs/emptinessCases", "inputs/valuePathCases",
            // Inputs and the form: the fields, the number field, the keyboard, the parts of a text field.
            "inputs/pathCases", "inputs/readCases", "inputs/textCases", "inputs/toggleCases", "inputs/clampCases", "inputs/linesCases",
            "inputs/keyboardCases", "inputs/keyActionCases", "inputs/errorCases", "inputs/geometryCases", "inputs/directionCases",
            "inputs/numberParseCases", "inputs/numberReadCases", "inputs/numberFieldCases", "inputs/numberInvalidCases", "inputs/numberKeyboardCases",
            "inputs/fieldBoxCases", "inputs/affixRowCases", "inputs/counterCases", "inputs/noteRowCases",
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
            // Screen anatomy: zones, app bar, navigation bar, top zone, insets.
            "bottom-zone/activeCases", "bottom-zone/childrenCases", "bottom-zone/floatPlaceCases", "bottom-zone/floatZoneCases", "bottom-zone/nav922RuleCases", "bottom-zone/navBadgeAppearScaleCases", "bottom-zone/navBadgeBoxCases", "bottom-zone/navBadgeCases", "bottom-zone/navBadgeDisappearOpacityCases", "bottom-zone/navBadgeMotionCases", "bottom-zone/navHeightCases", "bottom-zone/navShareCases", "bottom-zone/pinCases", "bottom-zone/ruleCases", "bottom-zone/sectionCases", "bottom-zone/zoneCases",
            "media-chrome/appBar916RuleCases", "media-chrome/appBarCases", "media-chrome/appBarChildrenCases", "media-chrome/appBarDrawnCases", "media-chrome/appBarLeadingCases", "media-chrome/appBarLeadingShownCases", "media-chrome/bodyTopCases", "media-chrome/glyphCases", "media-chrome/safeTopValueCases", "media-chrome/screenAppBarCases", "media-chrome/screenRuleCases", "media-chrome/screenTopPressCases", "media-chrome/screenTopRuleCases", "media-chrome/screenTopZoneCases", "media-chrome/scrolledLayerCases", "media-chrome/scrolledThresholdCases", "media-chrome/shownCases", "media-chrome/statusBarCases", "media-chrome/topZoneCases", "media-chrome/zoneCases",
            "top-zone/childrenCases", "top-zone/geometryCases", "top-zone/pinCases", "top-zone/ruleCases",
            "progress/ratioCases", "progress/ringCases", "progress/arcCases", "progress/barCases", "progress/paintCases", "progress/segmentCases", "progress/segmentPlaceCases", "progress/segmentPaintCases", "progress/segmentGradientCases", "progress/ringLabelCases", "progress/ringLabelNoneCases", "progress/capCases", "progress/segmentFillCases", "progress/segmentFillInlineCases", "progress/transitionShareCases", "progress/glowStepCases", "progress/glowRuleCases",
            "motion/poseCases",
            // Schema 9.28.0: the address of a picture, the list budget, the numbers of a theme, the paint of a text.
            "image-source/defaultCases", "image-source/neverAnImageCases", "repeater-filter/budgetCases",
            "theme-numbers/numberCases", "accessible-name/silentCases", "accessible-name/paintCases",
            // Modal layers: box, blur, drag, Escape, the event of a show.
            "sheet-dismiss/startCases", "sheet-dismiss/releaseCases", "sheet-dismiss/entryCases", "sheet-dismiss/ruleCases",
            "sheet-dismiss/escapeCases", "sheet-dismiss/escapeRuleCases",
            "media-chrome/modalCases", "media-chrome/modalAreaCases", "media-chrome/placementCases", "media-chrome/modalRuleCases", "media-chrome/shownCases",
            "motion/blurCases", "motion/glassCases",
            // Pictures: the proportion, the box, the file in the box, the radius, the circle.
            "media-chrome/ratioCases", "media-chrome/imageBoxCases", "media-chrome/paintCases", "media-chrome/radiusCases", "media-chrome/circleCases",
            "carousel/hostCases", "carousel/readCases", "carousel/cardCases", "carousel/stopCases", "carousel/activeCases",
            "carousel/dotCases", "carousel/areaCases", "carousel/stateCases", "repeater-filter/carouselCases",
            // Adaptive: width classes, visibility and style by class, the wide places, `unset`.
            // Placements: the map, the source of the map, what a place draws.
            "placements/readCases", "placements/sourceCases", "placements/showingCases",
            "placements/bodyCases", "placements/transportCases", "placements/eventCases", "placements/cacheCases",
            "adaptive/classCases", "adaptive/layerCases", "adaptive/styleCases", "adaptive/propsCases", "adaptive/showCases", "adaptive/drawnCases", "adaptive/numberCases", "adaptive/tabCases", "adaptive/weightCases", "adaptive/zoneCases", "adaptive/formCases", "adaptive/validityCases", "adaptive/contentCases", "adaptive/gridCases", "adaptive/boxCases", "adaptive/ruleCases", "adaptive/chromeCases", "adaptive/wideNavItemCases", "adaptive/wideNavHeightCases", "adaptive/wideAppBarCases", "adaptive/wideNavBarCases", "adaptive/wideZoneCases", "adaptive/wideModalCases", "adaptive/wideModalRadiusCases", "adaptive/wideModalContentCases", "adaptive/wideRuleCases", "adaptive/unsetStyleCases", "adaptive/unsetStateCases", "adaptive/unsetPropsCases",
        )

        /**
         * The builder's validator decides these; a reader draws whatever document it is given. Other
         * `…RuleCases` groups often hold the reader's rules too, so they are named in [run] instead.
         */
        fun isValidatorGroup(table: String, group: String): Boolean =
            group == "documentCases" || group == "schemaCases" || group.endsWith("DocumentCases") || group.endsWith("SchemaCases") ||
                group == "validityCases" || "$table/$group" in setOf(
                "inputs/ruleCases", "inputs/formRuleCases",
                // Where the editor puts a dropped child, not a reader's rule.
                "media-chrome/appBarPlaceCases", "inputs/placementCases",
                // Where a node may stand and how deep a document may go: what the builder may publish.
                "composition/nestingCases", "composition/depthCases", "actions/writeTreeCases",
                // Which documents may be published and what an editor advises about an older reader.
                // The worst case of a screen that the builder checks before it publishes.
                "repeater-filter/validatorCases",
                "adaptive/wideSectionCases", "adaptive/olderReaderShowOnCases", "adaptive/unsetScreenCases",
            )

        fun tables(): Map<String, Set<String>> {
            val root = java.io.File(Contract::class.java.getResource("/contract/fixtures")!!.toURI())
            return root.listFiles().orEmpty().filter { it.isDirectory && it.name != "themes" }.flatMap { folder ->
                folder.listFiles().orEmpty().filter { it.name.endsWith("-cases.json") }.map { folder.name to (Contract.groups(Contract.json("fixtures/${folder.name}/${it.name}")) - "records") }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, groups) -> groups.flatten().toSet() }
        }

        fun status(table: String, group: String): String? = when {
            "$table/$group" in run -> "run"
            isValidatorGroup(table, group) -> "validator"
            else -> null
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
}

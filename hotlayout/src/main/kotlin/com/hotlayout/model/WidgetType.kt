package com.hotlayout.model

/**
 * The widgets of the schema, by the word a document uses for them. A type that is not in the list is
 * [Unknown]: its children are drawn and it is reported.
 */
internal enum class WidgetType(val word: String?) {
    Screen("Screen"), AppBar("AppBar"), Column("Column"), Row("Row"), Container("Container"), Grid("Grid"),
    Stack("Stack"), Tabs("Tabs"), Tab("Tab"), Accordion("Accordion"), ChoiceGroup("ChoiceGroup"),
    Repeater("Repeater"), Spacer("Spacer"), Divider("Divider"), Text("Text"), Image("Image"), Icon("Icon"),
    Button("Button"), IconButton("IconButton"), ListTile("ListTile"), ProgressBar("ProgressBar"),
    CircularProgress("CircularProgress"), Badge("Badge"), NavBar("NavBar"), NavItem("NavItem"),
    TextField("TextField"), Switch("Switch"), Checkbox("Checkbox"),
    Unknown(null);

    companion object {
        private val byWord: Map<String, WidgetType> = entries.mapNotNull { type -> type.word?.let { it to type } }.toMap()

        fun of(type: String): WidgetType = byWord[type] ?: Unknown

        /** Types that can hold a carousel (the props decide). */
        val carouselHosts: Set<WidgetType> = setOf(Row, Repeater)

        /** Widgets whose style lands on their own surface (a role of the theme), not on the node box. */
        val roleSurfaces: Set<WidgetType> = setOf(Button, IconButton, Badge)
    }
}

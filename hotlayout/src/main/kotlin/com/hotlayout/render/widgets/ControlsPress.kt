package com.hotlayout.render.widgets

import kotlinx.coroutines.Job

/** What a press did for the control that sent it: who runs the action, and the host's task when it gave one. */
internal class PressRun(val runner: SwipeConfirmRules.Runner, val task: Job?)

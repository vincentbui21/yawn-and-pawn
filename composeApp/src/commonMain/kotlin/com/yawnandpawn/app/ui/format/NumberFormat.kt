package com.yawnandpawn.app.ui.format

/** [percent] (0 to 100) as a percentage in the phone's locale ("86%" in en-US, "86 %" in fr-FR). Never money. */
expect fun formatPercent(percent: Int): String

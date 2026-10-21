package com.ruterika.receiver

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug only: one focused text box, to check the phone keyboard end to end. */
class ImeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = TextView(this).apply { textSize = 28f; text = "Submitted: -" }
        val box = EditText(this).apply {
            textSize = 32f
            hint = "IME test"
            setSingleLine()
            setOnEditorActionListener { v, _, _ -> result.text = "Submitted: " + v.text; true }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(120, 120, 120, 120)
            addView(box)
            addView(result)
        })
        box.requestFocus()
    }
}

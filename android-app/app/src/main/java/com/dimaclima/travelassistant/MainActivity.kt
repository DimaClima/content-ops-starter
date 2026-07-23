package com.dimaclima.travelassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }
        content.addView(TextView(this).apply {
            text = "Travel Assistant Spain\nFiat Scudo / Android Auto"
            textSize = 24f
        })

        val actions = listOf(
            "Навигация" to "geo:0,0?q=destination",
            "Интересные места впереди" to "geo:0,0?q=interesting+places",
            "Парковка и ночёвка" to "geo:0,0?q=camper+parking",
            "Прогулка с собакой" to "geo:0,0?q=dog+park",
            "Погода" to "https://www.google.com/search?q=weather+near+me",
            "HVAC-магазины" to "geo:0,0?q=HVAC+supplies",
            "Зоны ZBE" to "https://www.google.com/search?q=ZBE+Spain+map",
            "ChatGPT" to "https://chatgpt.com"
        )

        actions.forEach { (title, uri) ->
            content.addView(Button(this).apply {
                text = title
                setOnClickListener { open(uri) }
            })
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun open(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        runCatching { startActivity(intent) }
    }
}

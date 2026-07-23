package com.dimaclima.travelassistant.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

class TravelCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = TravelScreen(carContext)
    }
}

private class TravelScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val items = listOf(
            "Навигация" to "geo:0,0?q=destination",
            "Интересные места" to "geo:0,0?q=interesting+places",
            "Парковка / ночёвка" to "geo:0,0?q=camper+parking",
            "Прогулка с собакой" to "geo:0,0?q=dog+park",
            "Погода" to "https://www.google.com/search?q=weather+near+me",
            "HVAC-магазины" to "geo:0,0?q=HVAC+supplies",
            "Зоны ZBE" to "https://www.google.com/search?q=ZBE+Spain+map"
        )

        val list = ItemList.Builder().apply {
            items.forEach { (title, uri) ->
                addItem(
                    Row.Builder()
                        .setTitle(title)
                        .setBrowsable(false)
                        .setOnClickListener { open(uri) }
                        .build()
                )
            }
        }.build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .setHeader(
                Header.Builder()
                    .setTitle("Travel Assistant Spain")
                    .setStartHeaderAction(Action.APP_ICON)
                    .build()
            )
            .build()
    }

    private fun open(uri: String) {
        runCatching {
            carContext.startCarApp(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }
    }
}

package tk.glucodata.mod.watch.face

import android.content.res.Resources
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import tk.glucodata.R

/**
 * JugglucoNGmod, watch block (part 3b): the face's one setting, "graph hours" 1 / 3 / 6 ([FaceGraph.HOURS], 3 by
 * default), as a `UserStyleSchema` list the system's watch face editor shows. Option ids are "1h", "3h", "6h".
 */
object FaceSettings {

    const val GRAPH_HOURS_ID = "mod_graph_hours"

    fun optionId(hours: Int): String = "${FaceGraph.sanitizeHours(hours)}h"

    /** The hours of an option id; anything unknown is [FaceGraph.DEFAULT_HOURS]. */
    fun hoursOf(optionId: String?): Int =
        FaceGraph.sanitizeHours(optionId?.removeSuffix("h")?.toIntOrNull() ?: FaceGraph.DEFAULT_HOURS)

    /** The graph hours chosen in [style]; [FaceGraph.DEFAULT_HOURS] when the setting is missing. */
    fun hoursOf(style: UserStyle): Int {
        val option = style.entries.firstOrNull { it.key.id.value == GRAPH_HOURS_ID }?.value
        return hoursOf(option?.id?.value?.let { String(it, Charsets.UTF_8) })
    }

    fun schema(resources: Resources): UserStyleSchema {
        // R ids read here, in a function body, never through `const val` (upstream's "0x0" bug)
        val names = mapOf(
            1 to R.string.mod_watch_face_graph_1,
            3 to R.string.mod_watch_face_graph_3,
            6 to R.string.mod_watch_face_graph_6,
        )
        val options = FaceGraph.HOURS.map { hours ->
            ListUserStyleSetting.ListOption(
                UserStyleSetting.Option.Id(optionId(hours)), resources, names.getValue(hours), null, emptyList(), null,
            )
        }
        val setting = ListUserStyleSetting(
            UserStyleSetting.Id(GRAPH_HOURS_ID),
            resources,
            R.string.mod_watch_face_graph_setting,
            R.string.mod_watch_face_graph_setting_description,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            options[FaceGraph.HOURS.indexOf(FaceGraph.DEFAULT_HOURS)],
        )
        return UserStyleSchema(listOf(setting))
    }
}

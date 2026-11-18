package mod.watch.face

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.watch.face.FaceGraph
import tk.glucodata.mod.watch.face.FaceSettings

/** JugglucoNGmod, watch block (part 3b): the face's one setting, graph hours 1 / 3 / 6, 3 by default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FaceSettingsTest {

    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun idOf(option: UserStyleSetting.Option) = String(option.id.value, Charsets.UTF_8)

    @Test
    fun oneListWithThreeOptionsAndThreeHoursByDefault() {
        val schema = FaceSettings.schema(resources)
        assertEquals(1, schema.userStyleSettings.size)
        val setting = schema.userStyleSettings[0] as ListUserStyleSetting
        assertEquals(FaceSettings.GRAPH_HOURS_ID, setting.id.value)
        assertEquals(listOf("1h", "3h", "6h"), setting.options.map { idOf(it) })
        assertEquals("3h", idOf(setting.defaultOption))
        assertEquals("Graph hours", setting.displayName.toString())
        assertEquals(listOf("1 hour", "3 hours", "6 hours"),
            setting.options.map { (it as ListUserStyleSetting.ListOption).displayName.toString() })
    }

    @Test
    fun theDefaultStyleGivesThreeHoursAndAChosenOptionItsHours() {
        val schema = FaceSettings.schema(resources)
        val repository = CurrentUserStyleRepository(schema)
        assertEquals(3, FaceSettings.hoursOf(repository.userStyle.value))
        val setting = schema.userStyleSettings[0] as ListUserStyleSetting
        val six = setting.options.first { idOf(it) == "6h" }
        assertEquals(6, FaceSettings.hoursOf(UserStyle(mapOf(setting to six))))
    }

    @Test
    fun optionIdsRoundTripAndAnythingElseIsTheDefault() {
        FaceGraph.HOURS.forEach { assertEquals(it, FaceSettings.hoursOf(FaceSettings.optionId(it))) }
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hoursOf("12h"))
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hoursOf("x"))
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hoursOf(null as String?))
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hoursOf(UserStyle(emptyMap())))
    }
}

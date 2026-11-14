package bg.rado.gaidaharmonia

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import bg.rado.gaidaharmonia.ui.GaidaApp
import bg.rado.gaidaharmonia.ui.GaidaTheme

class MainActivity : ComponentActivity() {
    private val vm: GaidaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // светла тема винаги — тъмни икони в статус лентата
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent { GaidaTheme { GaidaApp(vm) } }
    }
}

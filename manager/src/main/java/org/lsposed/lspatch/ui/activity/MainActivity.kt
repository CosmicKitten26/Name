package org.lsposed.lspatch.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import org.lsposed.lspatch.ui.page.PageList
import org.lsposed.lspatch.ui.theme.LSPTheme
import org.lsposed.lspatch.ui.util.LocalNavController
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.util.currentRoute

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navController = rememberAnimatedNavController()
            val currentRoute = navController.currentRoute
            var mainPage by rememberSaveable { mutableStateOf(PageList.Home) }

            LSPTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                CompositionLocalProvider(
                    LocalNavController provides navController,
                    LocalSnackbarHost provides snackbarHostState
                ) {
                    Scaffold(
                        bottomBar = {
                            MainNavigationBar(mainPage) {
                                mainPage = it
                                navController.navigate(it.name) {
                                    currentRoute?.let { route ->
                                        popUpTo(route) { inclusive = true }
                                    }
                                }
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbarHostState) }
                    ) { innerPadding ->
                        MainNavHost(navController, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}

@Composable
private fun MainNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(
        navController = navController,
        startDestination = PageList.Home.name,
        modifier = modifier
    ) {
        PageList.values().forEach { page ->
            val sb = StringBuilder(page.name)
            if (page.arguments.isNotEmpty()) {
                sb.append(page.arguments.joinToString(",", "?") { "${it.name}={${it.name}}" })
            }
            composable(route = sb.toString(), arguments = page.arguments, content = page.body)
        }
    }
}

@Composable
private fun MainNavigationBar(page: PageList, onClick: (PageList) -> Unit) {
    NavigationBar(tonalElevation = 8.dp) {
        arrayOf(PageList.Repo, PageList.Manage, PageList.Home, PageList.Logs, PageList.Settings).forEach {
            NavigationBarItem(
                selected = page == it,
                onClick = { onClick(it) },
                icon = {
                    if (page == it) Icon(it.iconSelected!!, it.title)
                    else Icon(it.iconNotSelected!!, it.title)
                },
                label = { Text(it.title) },
                alwaysShowLabel = false
            )
        }
    }
}

// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
import android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tailscale.ipn.Peer.RequestCodes
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.AboutView
import com.tailscale.ipn.ui.view.BugReportView
import com.tailscale.ipn.ui.view.DNSSettingsView
import com.tailscale.ipn.ui.view.ExitNodePicker
import com.tailscale.ipn.ui.view.IntroView
import com.tailscale.ipn.ui.view.LoginQRView
import com.tailscale.ipn.ui.view.MDMSettingsDebugView
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.ManagedByView
import com.tailscale.ipn.ui.view.MullvadExitNodePicker
import com.tailscale.ipn.ui.view.MullvadExitNodePickerList
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.PermissionsView
import com.tailscale.ipn.ui.view.RunExitNodeView
import com.tailscale.ipn.ui.view.SettingsView
import com.tailscale.ipn.ui.view.TailnetLockSetupView
import com.tailscale.ipn.ui.view.UserSwitcherView
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.SettingsNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private lateinit var requestVpnPermission: ActivityResultLauncher<Unit>
  private lateinit var navController: NavHostController

  companion object {
    private const val TAG = "Main Activity"
    private const val START_AT_ROOT = "startAtRoot"
  }

  private fun Context.isLandscapeCapable(): Boolean {
    return (resources.configuration.screenLayout and SCREENLAYOUT_SIZE_MASK) >=
        SCREENLAYOUT_SIZE_LARGE
  }

  // The loginQRCode is used to track whether or not we should be rendering a QR code
  // to the user.  This is used only on TV platforms with no browser in lieu of
  // simply opening the URL.  This should be consumed once it has been handled.
  private val loginQRCode: StateFlow<String?> = MutableStateFlow(null)

  @SuppressLint("SourceLockedOrientationActivity")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // (jonathan) TODO: Force the app to be portrait on small screens until we have
    // proper landscape layout support
    if (!isLandscapeCapable()) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    installSplashScreen()

    setContent {
      AppTheme {
        navController = rememberNavController()
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface(modifier = Modifier.universalFit()) { // Letterbox for AndroidTV
            NavHost(
                navController = navController,
                startDestination = "main",
                enterTransition = {
                  slideInHorizontally(animationSpec = tween(150), initialOffsetX = { it })
                },
                exitTransition = {
                  slideOutHorizontally(animationSpec = tween(150), targetOffsetX = { -it })
                },
                popEnterTransition = {
                  slideInHorizontally(animationSpec = tween(150), initialOffsetX = { -it })
                },
                popExitTransition = {
                  slideOutHorizontally(animationSpec = tween(150), targetOffsetX = { it })
                }) {
                  fun backTo(route: String): () -> Unit = {
                    navController.popBackStack(route = route, inclusive = false)
                  }

                  val mainViewNav =
                      MainViewNavigation(
                          onNavigateToSettings = { navController.navigate("settings") },
                          onNavigateToPeerDetails = {
                            navController.navigate("peerDetails/${it.StableID}")
                          },
                          onNavigateToExitNodes = { navController.navigate("exitNodes") },
                      )

                  val settingsNav =
                      SettingsNav(
                          onNavigateToBugReport = { navController.navigate("bugReport") },
                          onNavigateToAbout = { navController.navigate("about") },
                          onNavigateToDNSSettings = { navController.navigate("dnsSettings") },
                          onNavigateToTailnetLock = { navController.navigate("tailnetLock") },
                          onNavigateToMDMSettings = { navController.navigate("mdmSettings") },
                          onNavigateToManagedBy = { navController.navigate("managedBy") },
                          onNavigateToUserSwitcher = { navController.navigate("userSwitcher") },
                          onNavigateToPermissions = { navController.navigate("permissions") },
                          onBackToSettings = backTo("settings"),
                          onNavigateBackHome = backTo("main"))

                  val exitNodePickerNav =
                      ExitNodePickerNav(
                          onNavigateBackHome = {
                            navController.popBackStack(route = "main", inclusive = false)
                          },
                          onNavigateBackToExitNodes = backTo("exitNodes"),
                          onNavigateToMullvad = { navController.navigate("mullvad") },
                          onNavigateBackToMullvad = backTo("mullvad"),
                          onNavigateToMullvadCountry = { navController.navigate("mullvad/$it") },
                          onNavigateToRunAsExitNode = { navController.navigate("runExitNode") })

                  composable("main", enterTransition = { fadeIn(animationSpec = tween(150)) }) {
                    MainView(loginAtUrl = ::login, navigation = mainViewNav)
                  }
                  composable("settings") { SettingsView(settingsNav) }
                  composable("exitNodes") { ExitNodePicker(exitNodePickerNav) }
                  composable("mullvad") { MullvadExitNodePickerList(exitNodePickerNav) }
                  composable(
                      "mullvad/{countryCode}",
                      arguments =
                          listOf(navArgument("countryCode") { type = NavType.StringType })) {
                        MullvadExitNodePicker(
                            it.arguments!!.getString("countryCode")!!, exitNodePickerNav)
                      }
                  composable("runExitNode") { RunExitNodeView(exitNodePickerNav) }
                  composable(
                      "peerDetails/{nodeId}",
                      arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) {
                        PeerDetails(backTo("main"), it.arguments?.getString("nodeId") ?: "")
                      }
                  composable("bugReport") { BugReportView(backTo("settings")) }
                  composable("dnsSettings") { DNSSettingsView(backTo("settings")) }
                  composable("tailnetLock") { TailnetLockSetupView(backTo("settings")) }
                  composable("about") { AboutView(backTo("settings")) }
                  composable("mdmSettings") { MDMSettingsDebugView(backTo("settings")) }
                  composable("managedBy") { ManagedByView(backTo("settings")) }
                  composable("userSwitcher") {
                    UserSwitcherView(backTo("settings"), backTo("main"))
                  }
                  composable("permissions") {
                    PermissionsView(backTo("settings"), ::openApplicationSettings)
                  }
                  composable("intro", exitTransition = { fadeOut(animationSpec = tween(150)) }) {
                    IntroView(backTo("main"))
                  }
                }

            // Show the intro screen one time
            if (!introScreenViewed()) {
              navController.navigate("intro")
              setIntroScreenViewed(true)
            }
          }
        }

        // Login actions are app wide.  If we are told about a browse-to-url, we should render it
        // over whatever screen we happen to be on.
        loginQRCode.collectAsState().value?.let {
          LoginQRView(onDismiss = { loginQRCode.set(null) })
        }
      }
    }
    lifecycleScope.launch {
      Notifier.readyToPrepareVPN.collect { isReady ->
        if (isReady)
            App.getApplication().prepareVPN(this@MainActivity, RequestCodes.requestPrepareVPN)
      }
    }
  }

  init {
    // Watch the model's browseToURL and launch the browser when it changes or
    // pop up a QR code to scan
    lifecycleScope.launch {
      Notifier.browseToURL.collect { url ->
        url?.let {
          when (useQRCodeLogin()) {
            false -> Dispatchers.Main.run { login(it) }
            true -> loginQRCode.set(it)
          }
        }
      }
    }

    // Once we see a loginFinished event, clear the QR code which will dismiss the QR dialog.
    lifecycleScope.launch { Notifier.loginFinished.collect { _ -> loginQRCode.set(null) } }
  }

  // Returns true if we should render a QR code instead of launching a browser
  // for login requests
  private fun useQRCodeLogin(): Boolean {
    return AndroidTVUtil.isAndroidTV()
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    if (intent?.getBooleanExtra(START_AT_ROOT, false) == true) {
      navController.popBackStack(route = "main", inclusive = false)
    }
  }

  private fun login(urlString: String) {
    // Launch coroutine to listen for state changes. When the user completes login, relaunch
    // MainActivity to bring the app back to focus.
    App.getApplication().applicationScope.launch {
      try {
        Notifier.state.collect { state ->
          if (state > Ipn.State.NeedsMachineAuth) {
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                  action = Intent.ACTION_MAIN
                  addCategory(Intent.CATEGORY_LAUNCHER)
                  putExtra(START_AT_ROOT, true)
                }
            startActivity(intent)

            // Cancel coroutine once we've logged in
            this@launch.cancel()
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Login: failed to start MainActivity: $e")
      }
    }

    val url = urlString.toUri()
    try {
      val customTabsIntent = CustomTabsIntent.Builder().build()
      customTabsIntent.launchUrl(this, url)
    } catch (e: Exception) {
      // Fallback to a regular browser if CustomTabsIntent fails
      try {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, url)
        startActivity(fallbackIntent)
      } catch (e: Exception) {
        Log.e(TAG, "Login: failed to open browser: $e")
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch(Dispatchers.IO) {
      MDMSettings.update(App.getApplication(), restrictionsManager)
    }
  }

  override fun onStart() {
    super.onStart()

    // (jonathan) TODO: Requesting VPN permissions onStart is a bit aggressive.  This should
    // be done when the user initiall starts the VPN
    requestVpnPermission()
  }

  override fun onStop() {
    super.onStop()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch(Dispatchers.IO) {
      MDMSettings.update(App.getApplication(), restrictionsManager)
    }
  }

  private fun requestVpnPermission() {
    val vpnIntent = VpnService.prepare(this)
    if (vpnIntent != null) {
      val contract = VpnPermissionContract()
      requestVpnPermission =
          registerForActivityResult(contract) { granted ->
            Log.i("VPN", "VPN permission ${if (granted) "granted" else "denied"}")
          }
      requestVpnPermission.launch(Unit)
    }
  }

  private fun openApplicationSettings() {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
  }

  private fun introScreenViewed(): Boolean {
    return getSharedPreferences("introScreen", Context.MODE_PRIVATE).getBoolean("seen", false)
  }

  private fun setIntroScreenViewed(seen: Boolean) {
    getSharedPreferences("introScreen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("seen", seen)
        .apply()
  }
}

class VpnPermissionContract : ActivityResultContract<Unit, Boolean>() {
  override fun createIntent(context: Context, input: Unit): Intent {
    return VpnService.prepare(context) ?: Intent()
  }

  override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
    return resultCode == Activity.RESULT_OK
  }
}

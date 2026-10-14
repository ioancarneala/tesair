package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Phone controls only; the browser remains a clean CarPlay display. */
internal class BrowserDashboardView(
    context: Context,
    private val allowBackground: () -> Unit,
    private val enableRoute: () -> Unit,
    private val toggleRoute: () -> Unit,
    private val retry: () -> Unit,
    private val settings: () -> Unit,
    private val reviewSetup: () -> Unit = settings,
    private val allowConnection: () -> Unit = reviewSetup,
    private val openHotspot: () -> Unit = settings,
    private val openBluetooth: () -> Unit = settings,
    private val openVpn: () -> Unit = settings,
    private val openInternet: () -> Unit = settings,
    private val connectIphone: () -> Unit = reviewSetup,
    private val updateApp: () -> Unit = {},
) : ScrollView(context) {
    // Deliberately not a data class: a debug toString must never expose the Wi-Fi password.
    class State(
        val serverRunning: Boolean,
        val sessionRunning: Boolean,
        val iphoneActive: Boolean,
        val browserConnected: Boolean,
        val videoRendered: Boolean,
        val batteryAllowed: Boolean,
        val routeActive: Boolean,
        val routeEnabled: Boolean,
        val routeBusy: Boolean,
        val wireless: Boolean,
        val routeMessage: String,
        val networkName: String,
        val password: String,
        val trustedUrl: String?,
        val diagnostic: String,
        val setupNeedsAttention: Boolean = false,
        val secureAddressMessage: String? = null,
        val permissionsRequired: Boolean = false,
        val hotspotNeedsAttention: Boolean = false,
        val bluetoothRequired: Boolean = false,
        val otherVpn: Boolean = false,
        val receiverFailed: Boolean = false,
        val addressNeedsHelp: Boolean = false,
        val stopped: Boolean = false,
    ) {
        val pictureReady get() = !setupNeedsAttention && serverRunning && iphoneActive && browserConnected && videoRendered
        val ready get() = pictureReady && !permissionsRequired && batteryAllowed
        // A working picture cannot establish that Android will keep it running while locked.
        val step get() = when {
            permissionsRequired -> Step.PERMISSIONS
            !batteryAllowed -> Step.BACKGROUND
            stopped -> Step.STOPPED
            pictureReady -> Step.READY
            hotspotNeedsAttention -> Step.HOTSPOT
            bluetoothRequired -> Step.BLUETOOTH
            setupNeedsAttention -> Step.SETUP
            receiverFailed -> Step.RETRY
            !serverRunning || !sessionRunning -> Step.STARTING
            otherVpn -> Step.VPN
            wireless && !routeActive -> if (routeBusy) Step.ROUTE_STARTING else Step.ROUTE
            trustedUrl.isNullOrBlank() -> if (addressNeedsHelp) Step.ADDRESS_HELP else Step.ADDRESS_WAIT
            !iphoneActive -> Step.IPHONE
            !browserConnected -> Step.BROWSER
            else -> Step.VIDEO
        }
        val action get() = step.action
    }

    enum class Action { SETUP, BACKGROUND, ROUTE, RETRY, START, PERMISSIONS, HOTSPOT, BLUETOOTH, VPN, INTERNET, IPHONE, NONE }
    enum class Step(val action: Action = Action.NONE) {
        READY, STOPPED(Action.START), PERMISSIONS(Action.PERMISSIONS), HOTSPOT(Action.HOTSPOT), BLUETOOTH(Action.BLUETOOTH),
        SETUP(Action.SETUP), RETRY(Action.RETRY), STARTING, VPN(Action.VPN), ROUTE(Action.ROUTE),
        ROUTE_STARTING, ADDRESS_HELP(Action.INTERNET), ADDRESS_WAIT, IPHONE(Action.IPHONE), BROWSER, VIDEO,
        BACKGROUND(Action.BACKGROUND),
    }

    private val theme = BrowserDesign.onboarding(context)
    private val motion = BrowserMotion()
    private val centered = FrameLayout(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(28), dp(24), dp(32))
    }
    private val headline = text(30, true).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        isAccessibilityHeading = true
    }
    private val summary = text(16)
    private val illustration = BrowserOnboardingArtView(context, BrowserOnboardingArtView.Scene.IPHONE).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val primary = button("", true) { performPrimaryAction() }
    private val updateButton = button(string(R.string.browser_update_settings)) { updateApp() }.apply { visibility = View.GONE }
    private val phoneState = DetailRow(R.string.browser_details_iphone)
    private val browserState = DetailRow(R.string.browser_details_browser)
    private val networkName = text(22, true)
    private val networkHint = text(15)
    private val password = text(18)
    private val reveal = button(string(R.string.browser_dashboard_show_password)) {
        passwordVisible = !passwordVisible
        renderPassword()
    }
    private val address = text(20, true).apply {
        textDirection = View.TEXT_DIRECTION_LTR
        setTextIsSelectable(true)
    }
    private val addressHint = text(15)
    private val browserNetworkHint = text(15)
    private val backgroundState = DetailRow(R.string.browser_details_background)
    private val routeState = DetailRow(R.string.browser_details_tesla)
    private val contextualCards = column()
    private val savedConnectionCards = column()
    private val details = column().apply { visibility = View.GONE }
    private val technicalDetails = column().apply { visibility = View.GONE }
    private val directAddress = text(16, true).apply {
        textDirection = View.TEXT_DIRECTION_LTR
        setTextIsSelectable(true)
    }
    private val directAddressCard = BrowserDesign.setupCard(context).apply {
        visibility = View.GONE
        addView(sectionTitle(R.string.browser_details_direct_address))
        addView(directAddress, params(10))
    }
    private val routeDiagnostic = text(14).apply { setTextIsSelectable(true) }
    private val diagnostic = text(13).apply { setTextIsSelectable(true) }
    private val routeButton = button("") { toggleRoute() }
    private val retryButton = button(string(R.string.retry_carplay_connection)) { retry() }
    private val technicalButton = button(string(R.string.browser_details_show_technical)) {
        technicalDetails.visibility = if (technicalDetails.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateTechnicalButton()
    }
    private val detailsButton = button(string(R.string.browser_dashboard_show_details)) {
        details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (details.visibility != View.VISIBLE) {
            technicalDetails.visibility = View.GONE
            updateTechnicalButton()
            concealPassword()
        }
        updateDetailsButton()
        updateConnectionCards()
    }
    private lateinit var networkCard: LinearLayout
    private lateinit var addressCard: LinearLayout
    private var state: State? = null
    private var passwordVisible = false

    init {
        isFillViewport = true
        setBackgroundColor(theme.background)
        addView(centered, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        centered.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        content.addView(BrowserBrand.header(context).apply {
            addView(button(string(R.string.settings)) { settings() })
        })
        content.addView(headline, params(14))
        content.addView(summary, params(8))
        content.addView(illustration, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(210)).apply {
            topMargin = dp(16)
        })
        content.addView(primary, params(20))
        content.addView(updateButton, params(8))
        content.addView(contextualCards)
        content.addView(detailsButton, params(20))
        content.addView(details, params(4))

        BrowserDesign.setupCard(context).apply {
            details.addView(this)
            addView(sectionTitle(R.string.browser_dashboard_connection))
            addView(phoneState, params(14))
            addDivider(this)
            addView(browserState)
            addDivider(this)
            addView(backgroundState)
            addDivider(this)
            addView(routeState)
        }
        details.addView(savedConnectionCards)
        networkCard = BrowserDesign.setupCard(context).apply {
            addView(text(14, true).apply { text = string(R.string.browser_next_network_label) })
            addView(networkName, params(8))
            addView(networkHint, params(8))
            addView(password, params(14))
            addView(reveal, params(4))
        }
        addressCard = BrowserDesign.setupCard(context).apply {
            addView(text(14, true).apply { text = string(R.string.browser_next_address_label) })
            addView(address, params(10))
            addView(addressHint, params(10))
            addView(browserNetworkHint, params(10))
        }
        details.addView(technicalButton, params(12))
        details.addView(technicalDetails)
        technicalDetails.addView(directAddressCard, params(8))
        BrowserDesign.setupCard(context).apply {
            technicalDetails.addView(this, params(8))
            addView(sectionTitle(R.string.browser_details_diagnostics))
            addView(routeDiagnostic, params(12))
            addDivider(this)
            addView(diagnostic)
        }
        BrowserDesign.setupCard(context).apply {
            technicalDetails.addView(this, params(16))
            addView(sectionTitle(R.string.browser_details_recovery))
            addView(text(14).apply { text = string(R.string.browser_details_recovery_hint) }, params(8))
            addView(retryButton, params(12))
            addView(routeButton, params(4))
        }
        updateDetailsButton()
        updateTechnicalButton()
    }

    fun showUpdate(available: Boolean) { updateButton.visibility = if (available) View.VISIBLE else View.GONE }

    fun render(next: State) {
        if (state?.password != next.password || state?.networkName != next.networkName) passwordVisible = false
        state = next
        illustration.setScene(when (next.step) {
            Step.READY -> BrowserOnboardingArtView.Scene.READY
            Step.HOTSPOT, Step.ADDRESS_HELP -> BrowserOnboardingArtView.Scene.HOTSPOT
            Step.PERMISSIONS, Step.VPN, Step.ROUTE, Step.ROUTE_STARTING,
            Step.BACKGROUND -> BrowserOnboardingArtView.Scene.BACKGROUND
            Step.BROWSER, Step.VIDEO, Step.ADDRESS_WAIT -> BrowserOnboardingArtView.Scene.TESLA_BROWSER
            else -> BrowserOnboardingArtView.Scene.IPHONE
        })
        headline.update(string(title(next.step)))
        summary.update(string(hint(next.step)))
        phoneState.update(next.iphoneActive, if (next.iphoneActive)
            R.string.browser_details_connected else R.string.browser_details_waiting)
        browserState.update(next.pictureReady, when {
            next.pictureReady -> R.string.browser_details_picture_ready
            next.browserConnected -> R.string.browser_details_picture_waiting
            else -> R.string.browser_details_waiting
        })
        backgroundState.update(next.batteryAllowed, if (next.batteryAllowed)
            R.string.browser_details_allowed else R.string.browser_details_permission_needed)
        routeState.update(next.routeActive || !next.wireless, when {
            next.routeActive -> R.string.browser_details_enabled
            !next.wireless -> R.string.browser_details_not_needed
            next.otherVpn -> R.string.browser_details_other_vpn
            next.routeBusy -> R.string.browser_details_starting
            else -> R.string.browser_details_not_ready
        })
        networkName.update(next.networkName.ifBlank { string(R.string.browser_dashboard_network_missing) })
        networkHint.update(string(if (next.step == Step.HOTSPOT) R.string.onboarding_network_exact_hint
            else R.string.browser_dashboard_network_instruction))
        renderPassword()
        address.update(BrowserConnectionAddress.forInstructions(next.trustedUrl) ?: next.secureAddressMessage ?: string(when {
            next.stopped -> R.string.browser_details_address_idle
            next.serverRunning -> R.string.browser_dashboard_address_missing
            else -> R.string.browser_dashboard_server_starting
        }))
        addressHint.update(string(if (next.trustedUrl != null)
            R.string.browser_dashboard_browser_instruction else R.string.browser_dashboard_address_help))
        browserNetworkHint.update(if (next.networkName.isBlank()) "" else
            string(R.string.browser_next_same_network, next.networkName))
        primary.visibility = if (next.action == Action.NONE) View.GONE else View.VISIBLE
        primary.update(string(when (next.action) {
            Action.SETUP -> R.string.browser_dashboard_review_setup
            Action.BACKGROUND -> R.string.browser_battery_allow
            Action.ROUTE -> R.string.browser_address_enable
            Action.PERMISSIONS -> R.string.onboarding_permissions_action
            Action.HOTSPOT -> R.string.onboarding_network_settings
            Action.BLUETOOTH -> R.string.browser_next_open_bluetooth
            Action.VPN -> R.string.browser_next_open_vpn
            Action.INTERNET -> R.string.browser_next_open_internet
            Action.IPHONE -> R.string.onboarding_iphone_wifi_help
            Action.START -> R.string.onboarding_iphone_start
            else -> R.string.retry_carplay_connection
        }))
        routeButton.update(string(if (next.routeEnabled || next.routeActive)
            R.string.browser_dashboard_disable_route else R.string.browser_address_enable))
        routeButton.isEnabled = !next.routeBusy && (next.wireless || next.routeEnabled)
        routeButton.visibility = if (next.action == Action.ROUTE && !next.routeEnabled) View.GONE else View.VISIBLE
        retryButton.visibility = if (next.action == Action.RETRY || next.action == Action.START) View.GONE else View.VISIBLE
        routeDiagnostic.update(next.routeMessage)
        directAddress.update(next.trustedUrl.orEmpty())
        directAddressCard.visibility = if (next.trustedUrl.isNullOrBlank()) View.GONE else View.VISIBLE
        diagnostic.update(next.diagnostic)
        updateConnectionCards()
        motion.update(content, next.step)
    }

    fun concealPassword() {
        passwordVisible = false
        renderPassword()
    }

    private fun performPrimaryAction() {
        when (state?.action) {
            Action.SETUP -> reviewSetup()
            Action.BACKGROUND -> allowBackground()
            Action.ROUTE -> enableRoute()
            Action.RETRY, Action.START -> retry()
            Action.PERMISSIONS -> allowConnection()
            Action.HOTSPOT -> openHotspot()
            Action.BLUETOOTH -> openBluetooth()
            Action.VPN -> openVpn()
            Action.INTERNET -> openInternet()
            Action.IPHONE -> connectIphone()
            else -> Unit
        }
    }

    private fun renderPassword() {
        val secret = state?.password.orEmpty()
        password.visibility = if (secret.isEmpty()) View.GONE else View.VISIBLE
        reveal.visibility = password.visibility
        password.update(string(R.string.browser_dashboard_password, if (passwordVisible) secret else "••••••••"))
        reveal.update(string(if (passwordVisible) R.string.browser_dashboard_hide_password else R.string.browser_dashboard_show_password))
    }

    private fun updateDetailsButton() {
        detailsButton.update(string(if (details.visibility == View.VISIBLE)
            R.string.browser_dashboard_hide_details else R.string.browser_dashboard_show_details))
        updateExpansionDescription(detailsButton, details.visibility == View.VISIBLE)
    }

    private fun updateTechnicalButton() {
        technicalButton.update(string(if (technicalDetails.visibility == View.VISIBLE)
            R.string.browser_details_hide_technical else R.string.browser_details_show_technical))
        updateExpansionDescription(technicalButton, technicalDetails.visibility == View.VISIBLE)
    }

    private fun updateExpansionDescription(button: Button, expanded: Boolean) {
        button.contentDescription = string(R.string.browser_details_expansion,
            button.text, string(if (expanded) R.string.browser_details_expanded else R.string.browser_details_collapsed))
    }

    private fun updateConnectionCards() {
        val expanded = details.visibility == View.VISIBLE
        val target = if (expanded) savedConnectionCards else contextualCards
        for (card in listOf(networkCard, addressCard)) {
            if (card.parent !== target) {
                (card.parent as? ViewGroup)?.removeView(card)
                target.addView(card, params(16))
            }
        }
        contextualCards.visibility = if (expanded) View.GONE else View.VISIBLE
        networkCard.visibility = if (expanded || state?.step == Step.HOTSPOT) View.VISIBLE else View.GONE
        addressCard.visibility = if (expanded || state?.step == Step.BROWSER) View.VISIBLE else View.GONE
        networkHint.visibility = if (expanded) View.GONE else View.VISIBLE
        addressHint.visibility = if (expanded || state?.let {
            it.trustedUrl == null && it.secureAddressMessage != null
        } == true) View.GONE else View.VISIBLE
        browserNetworkHint.visibility = if (expanded || state?.networkName.isNullOrBlank()) View.GONE else View.VISIBLE
        if (networkCard.visibility != View.VISIBLE) concealPassword()
    }

    private fun title(step: Step) = when (step) {
        Step.READY -> R.string.browser_dashboard_ready
        Step.STOPPED -> R.string.browser_dashboard_waiting
        Step.BACKGROUND -> R.string.onboarding_battery_title
        Step.PERMISSIONS -> R.string.browser_next_permissions_title
        Step.HOTSPOT -> R.string.browser_next_hotspot_title
        Step.BLUETOOTH -> R.string.browser_next_bluetooth_title
        Step.SETUP -> R.string.browser_dashboard_setup_attention
        Step.RETRY -> R.string.browser_next_retry_title
        Step.STARTING -> R.string.browser_next_starting_title
        Step.VPN -> R.string.browser_next_vpn_title
        Step.ROUTE -> R.string.browser_next_route_title
        Step.ROUTE_STARTING -> R.string.browser_address_starting
        Step.ADDRESS_HELP -> R.string.browser_next_address_title
        Step.ADDRESS_WAIT -> R.string.browser_https_preparing
        Step.IPHONE -> R.string.browser_next_iphone_title
        Step.BROWSER -> R.string.browser_next_browser_title
        Step.VIDEO -> R.string.browser_next_video_title
    }

    private fun hint(step: Step) = when (step) {
        Step.READY -> R.string.browser_dashboard_ready_hint
        Step.STOPPED -> R.string.browser_setup_saved_hint
        Step.BACKGROUND -> R.string.browser_background_required
        Step.PERMISSIONS -> R.string.browser_next_permissions_hint
        Step.HOTSPOT -> R.string.browser_next_hotspot_hint
        Step.BLUETOOTH -> R.string.browser_next_bluetooth_hint
        Step.SETUP -> R.string.browser_dashboard_setup_attention_hint
        Step.RETRY -> R.string.browser_next_retry_hint
        Step.STARTING -> R.string.browser_next_starting_hint
        Step.VPN -> R.string.browser_next_vpn_hint
        Step.ROUTE -> R.string.browser_next_route_hint
        Step.ROUTE_STARTING, Step.ADDRESS_WAIT -> R.string.browser_next_preparing_hint
        Step.ADDRESS_HELP -> R.string.browser_next_address_hint
        Step.IPHONE -> R.string.browser_next_iphone_hint
        Step.BROWSER -> R.string.browser_next_browser_hint
        Step.VIDEO -> R.string.browser_next_video_hint
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
            width = minOf(w, dp(560))
        }
    }

    private inner class DetailRow(label: Int) : LinearLayout(context) {
        private val value = text(15)
        private val indicator = text(20, true).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(text(15, true).apply { text = string(label) })
                addView(value, params(4))
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(indicator, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) })
        }

        fun update(ready: Boolean, status: Int) {
            value.update(string(status))
            indicator.update(if (ready) "✓" else "○")
            indicator.setTextColor(if (ready) theme.success else theme.muted)
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun sectionTitle(resource: Int) = text(18, true).apply {
        text = string(resource)
        isAccessibilityHeading = true
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(context).apply {
            setBackgroundColor(theme.line)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(14)
            bottomMargin = dp(14)
        })
    }

    private fun text(size: Int, bold: Boolean = false) = BrowserDesign.setupText(context, size, bold)

    private fun button(title: String, primary: Boolean = false, click: () -> Unit) =
        BrowserDesign.setupButton(context, title, primary, click)

    private fun params(top: Int) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }
    private fun TextView.update(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = BrowserDesign.dp(context, value)
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
}

package org.futo.inputmethod.engine.general

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.stringPreferencesKey
import icu.astronot233.rime.DeployStage
import icu.astronot233.rime.Rime
import icu.astronot233.rime.RimeMessage
import icu.astronot233.rime.RimeSchema
import icu.astronot233.rime.SyncStage
import icu.astronot233.rime.X11Keys.XK_BackSpace
import icu.astronot233.rime.X11Keys.XK_Linefeed
import icu.astronot233.rime.X11Keys.XK_Return
import icu.astronot233.rime.X11Keys.XK_Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.Subtypes.switchToNextLanguage
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewAccessor
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.FloatingPreEdit
import org.futo.inputmethod.latin.uix.PreEditListener
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.namePreferenceKeyFor
import org.futo.inputmethod.latin.uix.preferenceKeyFor
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.CollapsibleSection
import org.futo.inputmethod.latin.uix.settings.DropDownPickerSettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.utils.ZipFileHelper
import org.futo.inputmethod.latin.utils.toEnumOrNull
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object ChineseIMESettings {
    enum class ChineseSimplificationMode(val stringResource: Int) {
        // These must not be renamed, or the existing setting value for users will break
        ByLanguage(R.string.chinese_setting_simplification_simplify_by_language_country),
        Simplified(R.string.chinese_setting_simplification_force_simplified),
        Traditional(R.string.chinese_setting_simplification_force_traditional);
    }

    internal fun initialPatch(pattern: String, substitution: String, twoWay: Boolean)
            = buildList {
        add("derive/^${pattern}/${substitution}/")
        if(twoWay) add("derive/^${substitution}/${pattern}/")
    }

    internal fun finalPatch(pattern: String, substitution: String, twoWay: Boolean)
            = buildList {
        add("derive/${pattern}$/${substitution}/")
        if(twoWay) add("derive/${substitution}$/${pattern}/")
    }

    enum class FuzzyPinyinModes(val schemePatch: List<String>, val displayName: String) {
        // These must not be renamed, or the existing setting value for users will break
        Z_ZH(initialPatch("z", "zh", true), "z = zh"),
        C_CH(initialPatch("c", "ch", true), "c = ch"),
        S_SH(initialPatch("s", "sh", true), "s = sh"),
        L_N(initialPatch("l", "n", true), "l = n"),
        F_H(initialPatch("f", "h", true), "f = h"),
        R_L(initialPatch("r", "l", false), "r = l"),
        K_G(initialPatch("k", "g", false), "k = g"),

        AN_ANG(finalPatch("an", "ang", true), "an = ang"),
        EN_ENG(finalPatch("en", "eng", true), "en = eng"),
        IN_ING(finalPatch("in", "ing", true), "in = ing"),
        IAN_IANG(finalPatch("ian", "iang", true), "ian = iang"),
        UAN_UANG(finalPatch("uan", "uang", true), "uan = uang");

        companion object {
            @JvmStatic
            fun fromCommaString(str: String) =
                str.split(',').mapNotNull { it.toEnumOrNull<FuzzyPinyinModes>() }.toSet()

            @JvmStatic
            fun toCommaString(value: Set<FuzzyPinyinModes>) =
                value.joinToString(",") { it.name }
        }
    }

    val SimplificationSetting = SettingsKey(
        stringPreferencesKey("ChineseIME_simplification"),
        ChineseSimplificationMode.ByLanguage.name
    )

    val PinyinSchemeSetting = SettingsKey(
        stringPreferencesKey("ChineseIME_pinyin_scheme"),
        "luna_pinyin"
    )

    val FuzzyPinyinSetting = SettingsKey(
        stringPreferencesKey("ChineseIME_fuzzy_pinyin"),
        ""
    )

    var schemaList: List<RimeSchema> = emptyList()
    val menu = UserSettingsMenu(
        title = R.string.chinese_settings_title,
        searchTags = R.string.chinese_setting_search_tags,
        navPath = "ime/zh", registerNavPath = true,
        settings = listOf(
            UserSetting(R.string.chinese_setting_simplification) {
                val resources = LocalResources.current
                val (setting, setSetting) = useDataStore(SimplificationSetting)
                DropDownPickerSettingItem<ChineseSimplificationMode>(
                    stringResource(R.string.chinese_setting_simplification),
                    ChineseSimplificationMode.entries,
                    setting.toEnumOrNull<ChineseSimplificationMode>() ?: ChineseSimplificationMode.ByLanguage,
                    { setSetting(it.name) },
                    { resources.getString(it.stringResource) }
                )
            },

            UserSetting(R.string.chinese_setting_pinyin_scheme) {
                val (setting, setSetting) = useDataStore(PinyinSchemeSetting)
                
                // 直接读取 RIME 引擎解析出来的方案列表
                val schemas = ChineseIMESettings.schemaList
                
                // 如果列表为空（说明后台还没部署完），给个兜底选项
                val availableIds = if (schemas.isEmpty()) {
                    listOf("luna_pinyin")
                } else {
                    schemas.map { it.schemaId }
                }

                DropDownPickerSettingItem<String>(
                    stringResource(R.string.chinese_setting_pinyin_scheme),
                    availableIds,       // 实际值（schemaId 的列表）
                    setting,            // 当前选中的值
                    { setSetting(it) }, // 选中后保存的值
                    { schemaId ->       // 显示给用户看的名字
                        if (schemas.isEmpty()) {
                            "全拼 (Full Pinyin)"
                        } else {
                            // 优先显示 RIME 方案内部的名字（如“雾凇拼音”），找不到再显示 ID
                            schemas.find { it.schemaId == schemaId }?.name ?: schemaId
                        }
                    }
                )
            },

            UserSetting(R.string.chinese_setting_fuzzy_pinyin) {
                val (setting, setSetting) = useDataStore(FuzzyPinyinSetting)
                val set = remember(setting) { FuzzyPinyinModes.fromCommaString(setting) }

                CollapsibleSection(stringResource(R.string.chinese_setting_fuzzy_pinyin)) {
                    FuzzyPinyinModes.entries.forEach { mode ->
                        SettingToggleRaw(mode.displayName, set.contains(mode), { enabled ->
                            val newSet = if (enabled) {
                                set + setOf(mode)
                            } else {
                                set - setOf(mode)
                            }

                            setSetting(FuzzyPinyinModes.toCommaString(newSet))
                        })
                    }
                }

            },

        )
    )
}

class InputKeeper() {
    var text = ""
        private set

    fun clear() {
        text = ""
    }

    fun append(codepoint: Int) {
        text += Char(codepoint)
    }

    fun backspace() {
        if(text.isNotEmpty()) text = text.take(text.length - 1)
    }

    fun replace(with: String) {
        text = with
    }
}

class ChineseIME(val helper: IMEHelper) : IMEInterface, SuggestionStripViewAccessor, PreEditListener {
    private val TAG = "ChineseIME (rime)"

    private val rime: Rime
    private val connect get() = helper.getCurrentInputConnection()
    private val coroScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val expandableUiCfg = ExpandableSuggestionBarConfiguration(true, false)
    private val maxBufferLength = 0x100000
    private var layoutHint: String? = null
    private val rawInput = InputKeeper()
    private val rimeLoading = mutableStateOf(false)

    override fun getLoadingState(): MutableState<Boolean>? = rimeLoading

    private val currentTransformation get() = when(layoutHint) {
        "stroke" -> StrokeTransformation
        else -> emptyMap()
    }

    private enum class EditingState(val active: Boolean) {
        NotEditing(false),
        Editing(true),
        Finishing(false)
    }
    private var editingState = EditingState.NotEditing

    companion object {
        @JvmStatic
        private val StrokeTransformation = mapOf(
            'h' to '⼀',
            's' to '⼁',
            'p' to '⼃',
            'n' to '⼂',
            'z' to '⼄',
        )

        @JvmStatic
        fun getRimeDir(context: Context) = context.getExternalFilesDir("rime")
                ?: throw IllegalStateException("Failed to access ExternalFilesDir!")

        @JvmStatic
        fun getShared(context: Context) = File(getRimeDir(context), "shared")

        @JvmStatic
        fun getUser(context: Context) = File(getRimeDir(context), "user")

        val PreviouslyExtractedDictionaryName = SettingsKey(
            stringPreferencesKey("ChineseDictionaryExtractedValue"),
            ""
        )
        var localPrevExtractedDictionaryName: String? = null

        fun resetSharedFromResources(context: Context, scope: CoroutineScope): Boolean {
            val pref = FileKind.Dictionary.preferenceKeyFor("zh")
            val namePref = FileKind.Dictionary.namePreferenceKeyFor("zh")
            val filePath = context.getSetting(pref, "")
            val file = File(context.applicationContext.getExternalFilesDir(null), filePath)

            val isUsingBuiltin = filePath.isEmpty() || !file.exists()
            
            // 这里定义 currentDictName，彻底告别 'name' 报错
            val currentDictName = if (isUsingBuiltin) {
                "builtin_dict_assets"
            } else {
                context.getSetting(namePref, "?")
            }

            if ((localPrevExtractedDictionaryName ?: context.getSetting(PreviouslyExtractedDictionaryName)) == currentDictName) {
                return false
            }

            try {
                val shared = getShared(context)
                // 清空现有的 shared 目录
                shared.walkBottomUp()
                    .fold(true) { res, it -> (it == shared || it.delete() || !it.exists()) && res }

                if (isUsingBuiltin) {
                    // 👇【修复 TAG 报错】：这里直接写死字符串，不用 TAG 变量
                    Log.i("ChineseIME (rime)", "No custom dictionary found, checking assets for built-in dictionaries...")
                    
                    val assetFiles = context.assets.list("")?.filter { it.endsWith(".dict") } ?: emptyList()
                    
                    if (assetFiles.isEmpty()) {
                        Log.w("ChineseIME (rime)", "No built-in dictionary found in assets.")
                        return true
                    }

                    for (dictName in assetFiles) {
                        Log.i("ChineseIME (rime)", "Extracting built-in dictionary: $dictName")
                        val builtinFile = File(context.cacheDir, dictName)
                        // 如果缓存里没有，先从 assets 复制到缓存
                        if (!builtinFile.exists()) {
                            context.assets.open(dictName).use { input ->
                                builtinFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        // 解压到 shared 目录
                        ZipFileHelper.extract(builtinFile, shared)
                    }
                    return true
                }

                // 👇【用户手动导入提取】
                Log.i("ChineseIME (rime)", "Extracting user imported dictionary: $filePath")
                ZipFileHelper.extract(file, shared)
                return true
                
            } catch (e: Exception) {
                Log.e("ChineseIME (rime)", "Failed to extract dictionary", e)
                return false
            } finally {
                localPrevExtractedDictionaryName = currentDictName
                scope.launch(Dispatchers.IO) {
                    context.setSetting(PreviouslyExtractedDictionaryName.key, currentDictName)
                }
            }
        }
    }

    init {
        val shared = getShared(helper.context)
        val user = getUser(helper.context)

        if(!shared.exists() || !user.exists()) {
            shared.mkdirs()
            user.mkdirs()
        }

        rime = Rime(shared.path, user.path, helper.context.packageName)
    }

    private fun handlePassByMessage(x11Code: Int, mask: Int) {
        when (x11Code) {
            XK_BackSpace -> sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
            XK_Tab       -> sendDownUpKeyEvent(KeyEvent.KEYCODE_TAB, 0)
            XK_Linefeed  -> sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER, 0)
            XK_Return    -> sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER, 0)
            else -> {
                if (rime.preeditFlow.value.isEmpty() || editingState.active) {
                    connect?.commitText(String(Character.toChars(x11Code)), 1)
                } else {
                    coroScope.launch {
                        rime.commitComposition()
                        rime.processX11Code(x11Code, mask)
                    }
                }
            }
        }
    }

    private fun subscribeToRimeMessage() = rime.messageFlow.onEach { msg -> when (msg) {
        is RimeMessage.Deploy -> when (msg.value) {
            DeployStage.Unknown -> Log.e(TAG, "Deploy: Failed")
            DeployStage.Startup -> Log.i(TAG, "Deploy: Startup")
            DeployStage.Success -> {
                Log.i(TAG, "Deploy: Success")
                coroScope.launch {
                    ChineseIMESettings.schemaList = rime.getSchemata().also {
                        Log.d(TAG, "Schemas: ${it.joinToString { it.toString() }} ")
                    }
                }
            }
            else -> {}
        }
        is RimeMessage.Sync -> when (msg.value) {
            SyncStage.Unknown -> Log.e(TAG, "Sync: Failed")
//            SyncStage.Startup ->
//            SyncStage.Success ->
            else -> {}
        }
        is RimeMessage.Commit -> {
            connect?.commitText(msg.value, 1)
        }
        is RimeMessage.Passby -> {
            val (x11Code, mask) = msg.value
            handlePassByMessage(x11Code, mask)
        }
        is RimeMessage.Unknown -> {
            Log.e(TAG, "Unrecognized error occurred: ${msg.value}")
        }
    }}.launchIn(coroScope)

    private fun subscribeToRimePreedit() = rime.preeditFlow.onEach { ped ->
        val notEditing = editingState == EditingState.NotEditing
        if(ped.isEmpty() && notEditing) { rawInput.clear() }

        helper.updateUiInputState(ped.isEmpty() && notEditing)
        helper.setPreedit(FloatingPreEdit.build(ped, this, currentTransformation, rawInput.text))
    }.launchIn(coroScope)

    override fun onStartEdit() {
        editingState = EditingState.Editing
    }

    // This assumes that the only valid characters for input simulation are ASCII letters
    // In particular, some characters like space will break the input by ending composition
    // (Maybe there are other valid symbols potentially?)
    private fun safeguardInputStringForSimulation(input: String): String {
        var string = input
        string = string.filter { it in 'a'..'z' || it in 'A'..'Z' }
        return string
    }

    private var updateEditJob: Job? = null
    override fun onUpdateEdit(string: String) {
        updateEditJob?.cancel()
        updateEditJob = coroScope.launch {
            ensureActive()
            rime.clearComposition()

            val converted = safeguardInputStringForSimulation(string)
            ensureActive()
            rime.simulateKeySequence(converted)
            rawInput.replace(converted)
        }
    }

    private var waitingToSelect: Pair<String, Int>? = null
    override fun onFinishEdit(string: String, candidateWord: String?, candidateIndex: Int?) {
        if(!editingState.active) return
        editingState = EditingState.Finishing

        coroScope.launch {
            rime.clearComposition()

            if(candidateWord != null && candidateIndex != null) {
                // TODO: This is kind of a buggy way of doing it
                waitingToSelect = candidateWord to candidateIndex
            }

            val converted = safeguardInputStringForSimulation(string)
            rime.simulateKeySequence(converted)
            rawInput.replace(converted)

            editingState = EditingState.NotEditing

            if(string.isEmpty()) {
                showSuggestionStrip(
                    SuggestedWords(
                        ArrayList(emptyList()),
                        ArrayList(emptyList()),
                        null,
                        false,
                        false,
                        false,
                        0,
                        0,
                        0
                    )
                )
            }
        }
    }

    private fun subscribeToRimeCandidates() = rime.candidatesFlow.onEach { cdd ->
        waitingToSelect?.let { sel ->
            if(cdd.getOrNull(sel.second)?.text == sel.first) {
                if(rime.selectCandidate(sel.second)) {
                    waitingToSelect = null
                    return@onEach
                } else {
                    Log.e(TAG, "Error selecting candidate!")
                }
            }
        }

        val suggestWordList = cdd.mapIndexed { index, candidate -> SuggestedWordInfo(
            candidate.text,
            "",
            Int.MAX_VALUE - index,
            1,
            null,
            SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWordInfo.NOT_A_CONFIDENCE,
            index,
            candidate.comment
        ) }.let { ArrayList(it) }

        // Don't flicker an empty suggest bar when editing
        if(editingState != EditingState.NotEditing && suggestWordList.isEmpty()) {
            return@onEach
        }

        showSuggestionStrip(
            SuggestedWords(
                suggestWordList,
                suggestWordList,
                null,
                false,
                false,
                false,
                0,
                0,
                0
            )
        )
    }.launchIn(coroScope)

    override fun onCreate() {
        if (!rime.startup(false))
            Log.e(TAG, "Error occurred!")
        subscribeToRimeMessage()
        subscribeToRimePreedit()
        subscribeToRimeCandidates()

        helper.lifecycleScope.launch {
            GlobalIMEMessage.collect { message ->
                when(message) {
                    IMEMessage.ReloadResources -> coroScope.launch {
                        rime.shutdown()
                        rime.startup(false)
                        prevConfiguration = null
                    }
                    else -> {}
                }
            }
        }

        blacklist.init()
    }
    override fun onDestroy() {
        helper.lifecycleScope.cancel()
        coroScope.cancel()
        rime.shutdown()
    }

    override fun onStartInput() {
        if(resetSharedFromResources(helper.context, helper.lifecycleScope)) {
            prevConfiguration = null
            coroScope.launch { rime.deploy() }
        }
        updateConfig()
    }

    private fun isSimplifiedChinese(locale: Locale): Boolean {
        if (locale.language != "zh") return false
        val setting = helper.context.getSetting(ChineseIMESettings.SimplificationSetting).toEnumOrNull<ChineseIMESettings.ChineseSimplificationMode>()
        return when(setting) {
            ChineseIMESettings.ChineseSimplificationMode.Simplified -> true
            ChineseIMESettings.ChineseSimplificationMode.Traditional -> false
            else -> {
                locale.script.takeIf { it.isNotEmpty() }?.let {
                    return it.equals("Hans", ignoreCase = true)
                }

                return when (locale.country.uppercase()) {
                    "TW", "HK", "MO" -> false
                    else -> true
                }
            }
        }
    }



    private data class Configuration(
        val schema: String,
        val learning: Boolean,
        val simplification: Boolean,
        val autocorrect: Boolean,
        val fuzzyMode: Set<ChineseIMESettings.FuzzyPinyinModes>
    )
    private var prevConfiguration: Configuration? = null

    private fun writeCustomizationFile(cfg: Configuration) {
        val schema = cfg.schema
        val file = File(getUser(helper.context), "${schema}.custom.yaml")
        val content = buildString {
            appendLine("patch:")

            // 这两个 translator 选项所有拼音方案通用
            appendLine("    translator/enable_correction: ${cfg.autocorrect}")
            appendLine("    translator/enable_user_dict: ${cfg.learning}")

            // 1. 只有原生的 luna_pinyin 需要注入简繁切换开关
            // 第三方方案（如雾凇、万象）自带简繁开关，不注入
            if (schema.startsWith("luna_pinyin")) {
                appendLine("    switches/+:")
                appendLine("        - name: futo_zh_simp")
                appendLine("          reset: ${if (cfg.simplification) 1 else 0}")
                appendLine("          states: [ 漢字, 汉字 ]")
                appendLine("    simplifier/option_name: futo_zh_simp")
            }

            // 2. 模糊音通用注入：只要是全拼类方案（排除双拼和笔画），统统加上模糊音规则
            // 这样无论是雾凇、万象，还是未来你导入的其他拼音方案，FUTO 设置里的模糊音都能生效
            if (!schema.startsWith("double_pinyin") && schema != "stroke") {
                appendLine("    speller/algebra/+:")
                cfg.fuzzyMode.forEach { mode ->
                    mode.schemePatch.forEach { rule ->
                        appendLine("        - $rule")
                    }
                }
            }
        }

        if (file.parentFile?.isDirectory == true) {
            file.writeText(content)
        }
    }

    private var configSemaphore = Semaphore(1)
    private fun updateConfig() {
        coroScope.launch {
            if(editingState.active) return@launch
            if(!configSemaphore.tryAcquire()) return@launch

            try {
                val settings = Settings.getInstance().current
                val locale = settings.mLocale

                val simplified = isSimplifiedChinese(locale)

                // 👇【新代码】：直接读取字符串设置，不再依赖枚举
                val schemaSettingId = helper.context.getSetting(ChineseIMESettings.PinyinSchemeSetting)

                val schema = when (layoutHint) {
                    "qwerty" -> {
                        // 如果选的是 luna_pinyin，根据简繁设置走系统自带的方案
                        if (schemaSettingId == "luna_pinyin") {
                            if (simplified) "luna_pinyin_simp" else "luna_pinyin"
                        } else {
                            // 如果选的是第三方方案（如 rime_ice / wanxiang），直接用它的 id
                            schemaSettingId
                        }
                    }
                    "stroke" -> {
                        "stroke"
                    }
                    null -> {
                        return@launch
                    }
                    else -> {
                        throwIfDebug(IllegalStateException("Invalid layout hint '${layoutHint}'"))
                        "luna_pinyin"
                    }
                }

                val fuzzy = ChineseIMESettings.FuzzyPinyinModes.fromCommaString(
                    helper.context.getSetting(ChineseIMESettings.FuzzyPinyinSetting)
                )

                val autocorrect = settings.mAutoCorrectionEnabledPerUserSettings
                        || settings.isSuggestionsEnabledPerUserSettings

                val learning = settings.isPersonalizationEnabled

                val config = Configuration(schema, learning, simplified, autocorrect, fuzzy)
                if (config != prevConfiguration) {
                    rimeLoading.value = true
                    writeCustomizationFile(config)
                    rime.deploy()

                    var result = false
                    for(i in 0..10) {
                        if(rime.selectSchema(config.schema)) {
                            result = true
                            break
                        }
                        Thread.sleep(300L)
                    }

                    if(!result) {
                        Log.e(TAG, "Failed to select schema 3s after deployment!")
                    }

                    rime.setOption("simplification", simplified)       // luna_pinyin_simp 用
                    rime.setOption("traditional", !simplified)         // luna_pinyin 用
                    rime.setOption("traditionalization", !simplified)  // rime_ice 用
                    prevConfiguration = config
                }
            } finally {
                configSemaphore.release()
                rimeLoading.value = false
            }
        }
    }

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {
        layoutHint = layout.mainLayout.imeHint
        if(helper.isImeActive(this)) updateConfig()
    }

    override fun onFinishInput() {
        if(editingState.active) return
        coroScope.launch {
            waitingToSelect = null
            rawInput.clear()
            rime.clearComposition()
        }
    }

    override fun onFinishSlidingInput() {
    }

    private fun interruptInput(text: CharSequence? = null) {
        coroScope.launch {
            rawInput.clear()
            rime.clearComposition()
        }
        connect?.finishComposingText()
        if (text != null) {
            connect?.commitText(text, 1)
        }
    }

    private fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0) {
        // NOTE: Modified based on InputLogic
        val eventTime = SystemClock.uptimeMillis()
        connect?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
        connect?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }
    private fun triggerIfIsAction(keyCode: Int): Boolean {
        if (Constants.CODE_ACTION_0 <= keyCode && keyCode <= Constants.CODE_ACTION_MAX) {
            val actionId: Int = keyCode - Constants.CODE_ACTION_0
            helper.triggerAction(actionId, false)
            return true
        }
        if (Constants.CODE_ALT_ACTION_0 <= keyCode && keyCode <= Constants.CODE_ALT_ACTION_MAX) {
            val actionId: Int = keyCode - Constants.CODE_ALT_ACTION_0
            helper.triggerAction(actionId, true)
            return true
        }
        return false
    }
    override fun onEvent(event: Event) {
        helper.requestCursorUpdate()
        when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> {
                if (triggerIfIsAction(event.mKeyCode))
                    return
                val x11Code = when (event.mKeyCode) {
                    Constants.CODE_DELETE -> '\b'.code
                    Event.NOT_A_KEY_CODE -> event.mCodePoint
                    else -> return
                }.let { when (it) {
                    '\b'.code -> XK_BackSpace
                    '\t'.code -> XK_Tab
                    '\n'.code -> XK_Return // Use return instead of linefeed here.
                    '\r'.code -> XK_Return
                    else -> it
                } }

                if(!editingState.active) {
                    // Only update outside editing state. We cannot update inside editing state,
                    // because the cursor position is unknown and we assume it's always at the end
                    if(x11Code == XK_BackSpace) {
                        rawInput.backspace()
                    } else if(x11Code == XK_Return || x11Code == ' '.code) {
                        rawInput.clear()
                    } else if(event.mKeyCode == Event.NOT_A_KEY_CODE) {
                        rawInput.append(event.mCodePoint)
                    }

                    coroScope.launch { rime.processX11Code(x11Code) }
                } else {
                    handlePassByMessage(x11Code, 0)
                }
            }

            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                val suggestion = event.mSuggestedWordInfo ?: return
                if (suggestion.isKindOf(SuggestedWordInfo.KIND_UNDO)) {
                    connect?.commitText(suggestion.word, 1)
                } else {
                    coroScope.launch {
                        if(!rime.selectCandidate(suggestion.mCandidateIndex)) {
                            Log.e(TAG, "Couldn't select candidate")
                        }
                    }
                }
            }

            Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> {
                interruptInput(event.mText)
            }

            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                interruptInput()
                if (event.mX == KeyEvent.META_CTRL_ON) when (event.mKeyCode) {
                    KeyEvent.KEYCODE_F1 -> {

                    }
                    KeyEvent.KEYCODE_F2 -> {
                        Log.d(TAG, "Redeploying...")
                        coroScope.launch { rime.deploy() }
                        return
                    }
                }
                sendDownUpKeyEvent(event.mKeyCode, event.mX)
            }

            else -> {}
        }
    }

    private var anchorOutOfDate = true
    private var anchorLeftCount = 0
    private var anchorRightCount = 0
    private val currentAnchorCursor: Pair<Int, Int> get() {
        if (anchorOutOfDate) {
            anchorLeftCount = (connect?.getTextBeforeCursor(maxBufferLength, 0) ?: "").length
            anchorRightCount = (connect?.getTextAfterCursor(maxBufferLength, 0) ?: "").length
            currentMovingCursor = anchorLeftCount
            anchorOutOfDate = false
        }
        return Pair(anchorLeftCount, anchorRightCount)
    }
    private var currentMovingCursor: Int = 0
    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {
        var meta = 0
        if (stepOverWords) meta = meta or KeyEvent.META_CTRL_ON
        if (select == true) meta = meta or KeyEvent.META_SHIFT_ON
        if(steps < 0) {
            for(i in 0 until -steps) {
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta)
            }
        } else {
            for(i in 0 until steps) {
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta)
            }
        }
    }
    override fun onUpWithPointerActive() {
        // TODO("Unsupported yet")
    }
    override fun onMoveDeletePointer(steps: Int) {
        setNeutralSuggestionStrip()
        val (lBound, rBound) = currentAnchorCursor
        currentMovingCursor = when {
            steps < 0 -> max(0, currentMovingCursor - 1)
            steps > 0 -> min(lBound + rBound, currentMovingCursor + 1)
            else -> currentMovingCursor
        }
        connect?.setSelection(lBound, currentMovingCursor)
    }
    override fun onUpWithDeletePointerActive() {
        anchorOutOfDate = true
        val selection: CharSequence? = connect?.getSelectedText(0)
        if (selection == null) {
            onUpWithPointerActive()
            return
        }
        coroScope.launch {
            val preedit = rime.getPreedit()
            var reserved: String
            when {
                preedit.endsWith(selection) -> {
                    reserved = preedit.removeSuffix(selection).filterNot { it.isWhitespace() }
                    connect?.commitText(reserved, 1)
                }
                selection.endsWith(preedit) -> {
                    reserved = selection.removeSuffix(preedit) as String
                    sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
                }
                else -> {
                    sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
                    Log.w(TAG, "Wrong preedit text fetched?")
                    return@launch
                }
            }
            rawInput.clear()
            rime.clearComposition()
            val info = arrayListOf(SuggestedWordInfo(
                reserved,
                "",
                Int.MAX_VALUE,
                SuggestedWordInfo.KIND_UNDO,
                null,
                0,
                0
            ))
            showSuggestionStrip(SuggestedWords(
                info,
                null,
                null,
                false,
                false,
                false,
                0,
                0
            ))
        }
    }

    override fun onSwipeLanguage(direction: Int) {
        switchToNextLanguage(helper.context, direction)
    }

    private var prevSuggest: SuggestedWords? = null
    private val blacklist = SuggestionBlacklist(Settings.getInstance(), helper.context, helper.lifecycleScope)
    override fun setNeutralSuggestionStrip() {
        prevSuggest = null
        helper.setNeutralSuggestionStrip(expandableUiCfg)
    }
    override fun showSuggestionStrip(suggestedWords: SuggestedWords?) {
        val words = suggestedWords?.let { blacklist.filterBlacklistedSuggestions(it) }
        prevSuggest = words
        helper.showSuggestionStrip(words, expandableUiCfg)
    }

    override fun requestSuggestionRefresh() {
        showSuggestionStrip(null)
    }

    override fun isGestureHandlingAvailable() : Boolean = false
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}

    override fun clearUserHistoryDictionaries() {
        Log.d(TAG, "Clearing Chinese dictionary...")

        coroScope.launch {
            rime.shutdown()
            getUser(helper.context).let {
                it.deleteRecursively()
                it.mkdirs()
            }
            rime.startup(false)
            prevConfiguration = null
        }
    }

    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}

// Non-behavior methods {{
// NOTE: These methods are scheduled to have no behavior
    override fun onCancelInput() {}
    override fun onDeviceUnlocked() {}
    override fun onOrientationChanged() {}
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingSpanStart: Int, composingSpanEnd: Int) {}
// Non-behavior methods }}

    val debugInfo: String
        get() = "configuration=${prevConfiguration}\nlayoutHint=${layoutHint}\nlocale=${Settings.getInstance().current.mLocale}\nisSimplified=${isSimplifiedChinese(Settings.getInstance().current.mLocale)}\nrawInput=${rawInput.text}"
}

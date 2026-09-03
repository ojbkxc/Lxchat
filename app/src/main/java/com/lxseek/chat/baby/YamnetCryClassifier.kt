package com.lxseek.chat.baby

import android.content.Context
import com.lxseek.chat.util.DebugLog
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 一次 YAMNet 推理的可解释多类分数。只反序列化监护关心的有限语义类，
 * 字段含义（AudioSet display name，索引见 [YamnetCryClassifier.FALLBACK_INDEXES]）：
 *  - [cry]          低声哼唧 = Baby cry + Whimper（组内各类求和）
 *  - [intenseCry]   剧烈大哭 = Crying, sobbing（独立分级）
 *  - [speech]       成人说话 = Speech（用于"有人在哄"抑制）
 *  - [cough]        咳嗽 / 清嗓 = Cough, Throat clearing
 *  - [sneeze]       打喷嚏 = Sneeze
 *  - [scream]       尖叫 / 高声喊叫 = Screaming, Shout, Yell, Children shouting
 *  - [laughter]     欢笑 = Laughter, Baby laughter, Chuckle, Giggle
 *  - [childSpeech]  牙牙学语 = Child speech, Babbling
 *  - [whiteNoise]   白噪音来源 = White noise, Vacuum cleaner, Hair dryer, Fan（取组内最大）
 *  - [door]         门/柜开关 = Door, Sliding door, Knock, Cupboard, Drawer（取组内最大）
 *
 * 除 [cry] 为求和外，其余事件类在逐帧布局下取组内各类的最大值，保证单帧只贡献一次
 * 峰值信号、不因组内多类同时触发而虚高。
 */
data class YamnetScores(
    val cry: Float = 0f,
    val intenseCry: Float = 0f,
    val speech: Float = 0f,
    val cough: Float = 0f,
    val sneeze: Float = 0f,
    val scream: Float = 0f,
    val laughter: Float = 0f,
    val childSpeech: Float = 0f,
    val whiteNoise: Float = 0f,
    val door: Float = 0f,
    // 新增 11 类：Dog bark / Cat / Bird / Glass break / Siren / Phone ring / Clap / Whale / Water / Music
    val dogBark: Float = 0f,
    val cat: Float = 0f,
    val bird: Float = 0f,
    val glassBreak: Float = 0f,
    val siren: Float = 0f,
    val phoneRing: Float = 0f,
    val clap: Float = 0f,
    val whistle: Float = 0f,
    val footsteps: Float = 0f,
    val water: Float = 0f,
    val music: Float = 0f,
    // 农场动物类（复用 YAMNet 已有 AudioSet 类，无需重新训练）：
    val pig: Float = 0f,
    val cow: Float = 0f,
    val chicken: Float = 0f,
    val horse: Float = 0f,
    val sheep: Float = 0f,
)

/**
 * YAMNet TFLite 推理封装（AudioSet 521 类音频事件分类）。
 *
 * 模型：`lite-model_yamnet_tflite_1.tflite`（TensorFlow Hub 官方全精度 float32 版，
 * ~16MB，非量化），下载地址见 [BabyModelManager.DOWNLOAD_URL]。输入 16kHz 单声道
 * float PCM，每次推理至少 ~0.975s（15600 样本）。
 *
 * 两种输出形态都被支持（不同来源的 YAMNet 转换版布局不同）：
 *  - `[1, frames, 521]`（3D）——逐帧分数，事件类逐帧取最大值（对齐 crywatch 的
 *    "peak per-frame probability"）；
 *  - `[1, 521]`（2D）——整段平均分数，直接读各类。
 *
 * 类别索引按名字在运行期解析（不硬编码下标，抗模型版本差异）；labels 文件缺失时
 * 回退到官方固定索引。
 */
class YamnetCryClassifier(context: Context, modelFile: File) {

    companion object {
        private const val TAG = "YamnetCryClassifier"

        /** YAMNet 官方 TFLite 的输入是 [1, 15600]（0.975s @16kHz）。 */
        const val INPUT_SAMPLES = 15600
        const val NUM_CLASSES = 521

        /** 低声哼唧（配 [YamnetScores.cry]）：Baby cry + Whimper。 */
        val CRY_CLASS_NAMES = listOf("Baby cry, infant cry", "Whimper")

        /** 剧烈大哭（独立分级，配 [YamnetScores.intenseCry]）：Crying, sobbing。 */
        val INTENSE_CRY_CLASS_NAMES = listOf("Crying, sobbing")

        /** 语音类（用于"有人在哄"抑制）。 */
        const val SPEECH_CLASS_NAME = "Speech"

        /** 咳嗽相关类（Cough + 清嗓）。 */
        val COUGH_CLASS_NAMES = listOf("Cough", "Throat clearing")

        /** 打喷嚏类。 */
        val SNEEZE_CLASS_NAMES = listOf("Sneeze")

        /** 尖叫 / 高声喊叫（剧烈哭的伴随信号 + 独立异常事件）。 */
        val SCREAM_CLASS_NAMES = listOf("Screaming", "Shout", "Yell", "Children shouting")

        /** 笑声（含婴儿笑），用于判断"开心互动"。 */
        val LAUGHTER_CLASS_NAMES = listOf("Laughter", "Baby laughter", "Chuckle, chortle", "Giggle")

        /** 宝宝牙牙学语 / 咿呀，用于判断"醒着在互动"。 */
        val CHILD_SPEECH_CLASS_NAMES = listOf("Child speech, kid speaking", "Babbling")

        /** 白噪音来源类（取组内最大）：白噪声 / 真空吸尘 / 吹风机 / 风扇。 */
        val WHITE_NOISE_CLASS_NAMES = listOf(
            "White noise", "Vacuum cleaner", "Hair dryer", "Mechanical fan",
        )

        /** 门 / 柜开关声，用于判断"可能有人进出"。 */
        val DOOR_CLASS_NAMES = listOf(
            "Door", "Sliding door", "Knock", "Cupboard open or close", "Drawer open or close",
        )

        /** 狗叫（配 [YamnetScores.dogBark]）。 */
        val DOG_BARK_CLASS_NAMES = listOf("Dog", "Bark")

        /** 猫叫 / 呼噜。 */
        val CAT_CLASS_NAMES = listOf("Meow", "Purr", "Cat")

        /** 鸟鸣 / 啁啾。 */
        val BIRD_CLASS_NAMES = listOf(
            "Bird", "Bird vocalization, bird call, bird song", "Chirp, tweet",
        )

        /** 玻璃碎裂。 */
        val GLASS_BREAK_CLASS_NAMES = listOf("Glass", "Shatter")

        /** 警笛 / 警报（消防车 / 救护车 / 警车）。 */
        val SIREN_CLASS_NAMES = listOf(
            "Siren", "Civil defense siren", "Fire engine, fire truck (siren)",
            "Ambulance (siren)", "Police car (siren)",
        )

        /** 电话铃声 / 手机铃声。 */
        val PHONE_RING_CLASS_NAMES = listOf("Telephone", "Telephone bell ringing", "Ringtone")

        /** 拍手。 */
        val CLAP_CLASS_NAMES = listOf("Clapping")

        /** 口哨。 */
        val WHISTLE_CLASS_NAMES = listOf("Whistle", "Whistling")

        /** 脚步声。 */
        val FOOTSTEPS_CLASS_NAMES = listOf("Footsteps")

        /** 水流 / 雨 / 水花。 */
        val WATER_CLASS_NAMES = listOf("Water", "Rain", "Splash, splatter")

        /** 音乐播放。 */
        val MUSIC_CLASS_NAMES = listOf("Music")

        /** 农场动物：猪（Pig / Oink）。 */
        val PIG_CLASS_NAMES = listOf("Pig", "Oink")

        /** 农场动物：牛 / 奶牛（Cattle, bovinae / Moo）。 */
        val COW_CLASS_NAMES = listOf("Cattle, bovinae", "Moo")

        /** 农场动物：鸡 / 公鸡（Chicken, rooster / Cluck / Crowing）。 */
        val CHICKEN_CLASS_NAMES = listOf(
            "Chicken, rooster", "Cluck", "Crowing, cock-a-doodle-doo",
        )

        /** 农场动物：马（Horse / Neigh, whinny / Clip-clop）。 */
        val HORSE_CLASS_NAMES = listOf("Horse", "Neigh, whinny", "Clip-clop")

        /** 农场动物：羊（Sheep / Bleat）。 */
        val SHEEP_CLASS_NAMES = listOf("Sheep", "Bleat")

        /** 模型旁边的类别名清单文件（下载器一并落盘）。 */
        const val LABELS_FILE_NAME = "yamnet_labels.txt"

        /**
         * TensorFlow Hub `google/yamnet/1` 官方 class map（CSV display_name 列）固定行号。
         * 查询自官方 yamnet_class_map.csv（519 行 display_name）。
         * 顺序须与 [BabyModelManager] 落盘的 YAMNET_CLASS_NAMES 完全一致：
         * index 0=Speech / 1=Child speech / … / 11=Screaming / 13=Laughter /
         * 19=Crying, sobbing / 20=Baby cry / 21=Whimper / 42=Cough / 44=Sneeze /
         * 348=Door / 353=Knock / 367=Hair dryer / 371=Vacuum / 406=Fan / 514=White noise。
         */
        val FALLBACK_INDEXES: Map<String, Int> = mapOf(
            "Speech" to 0,
            "Child speech, kid speaking" to 1,
            "Babbling" to 4,
            "Shout" to 6,
            "Yell" to 9,
            "Children shouting" to 10,
            "Screaming" to 11,
            "Laughter" to 13,
            "Baby laughter" to 14,
            "Giggle" to 15,
            "Chuckle, chortle" to 18,
            "Crying, sobbing" to 19,
            "Baby cry, infant cry" to 20,
            "Whimper" to 21,
            "Cough" to 42,
            "Throat clearing" to 43,
            "Sneeze" to 44,
            "Door" to 348,
            "Sliding door" to 351,
            "Knock" to 353,
            "Cupboard open or close" to 356,
            "Drawer open or close" to 357,
            "Hair dryer" to 367,
            "Vacuum cleaner" to 371,
            "Mechanical fan" to 406,
            "White noise" to 514,
            // 额外事件类（index 逐一核对官方 yamnet_class_map.csv）：
            "Clapping" to 58,
            "Walk, footsteps" to 48,
            "Whistling" to 35,
            "Dog" to 69,
            "Bark" to 70,
            "Cat" to 76,
            "Purr" to 77,
            "Meow" to 78,
            "Bird" to 106,
            "Bird vocalization, bird call, bird song" to 107,
            "Chirp, tweet" to 108,
            "Music" to 132,
            // 农场动物（官方 yamnet_class_map.csv 索引逐一核对）：
            "Pig" to 88,
            "Oink" to 89,
            "Cattle, bovinae" to 85,
            "Moo" to 86,
            "Chicken, rooster" to 94,
            "Cluck" to 95,
            "Crowing, cock-a-doodle-doo" to 96,
            "Horse" to 82,
            "Neigh, whinny" to 84,
            "Clip-clop" to 83,
            "Sheep" to 92,
            "Bleat" to 91,
            // 注：Glass / Siren / Phone / Water 等类未提供兜底索引（依赖 labels 文件按名
            // 解析）；labels 缺失时这些类不触发（安全降级），避免错误索引误判。
        )
    }

    private val interpreter: Interpreter
    private val cryIndices: IntArray
    private val intenseCryIndices: IntArray
    private val speechIndex: Int
    private val coughIndices: IntArray
    private val sneezeIndices: IntArray
    private val screamIndices: IntArray
    private val laughterIndices: IntArray
    private val childSpeechIndices: IntArray
    private val whiteNoiseIndices: IntArray
    private val doorIndices: IntArray
    private val dogBarkIndices: IntArray
    private val catIndices: IntArray
    private val birdIndices: IntArray
    private val glassBreakIndices: IntArray
    private val sirenIndices: IntArray
    private val phoneRingIndices: IntArray
    private val clapIndices: IntArray
    private val whistleIndices: IntArray
    private val footstepsIndices: IntArray
    private val waterIndices: IntArray
    private val musicIndices: IntArray
    private val pigIndices: IntArray
    private val cowIndices: IntArray
    private val chickenIndices: IntArray
    private val horseIndices: IntArray
    private val sheepIndices: IntArray

    /** 输出是否为逐帧 3D 布局（true=3D, false=2D），按输出张量秩判定。 */
    private val frameLayout: Boolean

    /**
     * 输入张量秩：
     *  - 1 = 扁平波形 `[N]`（TF Hub 全精度版为动态 `[-1]`，报告 shape `[1]`）
     *  - 2 = `[1, N]`
     */
    private val inputRank: Int

    init {
        // 关闭 XNNPACK delegate：该 TF Hub 转换版输入为动态形态，XNNPACK 对动态 shape 的
        // 兼容性历史上有原生层 abort 风险（表现为闪退）。这里改用内置稳定 kernel
        // （numThreads=2 仍并行），安全性优先于这点性能。
        val options = Interpreter.Options().apply {
            numThreads = 2
            setUseXNNPACK(false)
        }
        interpreter = Interpreter(loadModel(context, modelFile), options)
        // TFLite Java 在 allocateTensors() 之前不允许读取张量形态，先分配一次。
        interpreter.allocateTensors()

        // YAMNet TFLite 各版本输入形态不一致，必须显式统一：
        //  - TF Hub 全精度版（App 默认下载源，~16MB）：rank-1 **动态** [-1]（shape 报 [1]）。
        //    若不在推理前把输入张量 resize 到窗长，Interpreter.run 会因缓冲大小与张量
        //    大小（默认 1 个元素）不匹配而失败——该异常在部分 TFLite 版本走原生层 abort，
        //    无法被 Kotlin try/catch 捕获，表现为「启用哭声监护后直接闪退」。
        //  - 参考实现 baby-monitor 的复刻版：rank-1 固定 [15600]。
        //  - 其余转换版：rank-2 [1, 15600]。
        // 这里按秩把输入张量显式 resize 到推理窗长，固定 shape 的模型 resize 到同形是
        // no-op（安全）；动态模型则由此拿到正确形态。
        val inputShape = interpreter.getInputTensor(0).shape()
        inputRank = inputShape.size
        val targetShape = when (inputRank) {
            1 -> intArrayOf(INPUT_SAMPLES)
            2 -> intArrayOf(1, INPUT_SAMPLES)
            else -> {
                interpreter.close()
                throw IllegalArgumentException("不支持的 YAMNet 输入秩: $inputRank")
            }
        }
        if (!inputShape.contentEquals(targetShape)) {
            interpreter.resizeInput(0, targetShape)
            interpreter.allocateTensors()
        }

        val outputShape = interpreter.getOutputTensor(0).shape()
        frameLayout = outputShape.size == 3
        // 类别名在部分 TFLite 转换里无法从张量元数据读取；这里靠伴随的 labels 文件
        // （BabyModelManager 下载时一并落盘）。读不到时退回官方固定索引。
        val labels = loadLabels(modelFile)
        val resolver = LabelResolver(labels)
        cryIndices = resolve(resolver, CRY_CLASS_NAMES)
        intenseCryIndices = resolve(resolver, INTENSE_CRY_CLASS_NAMES)
        speechIndex = resolver.indexOf(SPEECH_CLASS_NAME) ?: -1
        coughIndices = resolve(resolver, COUGH_CLASS_NAMES)
        sneezeIndices = resolve(resolver, SNEEZE_CLASS_NAMES)
        screamIndices = resolve(resolver, SCREAM_CLASS_NAMES)
        laughterIndices = resolve(resolver, LAUGHTER_CLASS_NAMES)
        childSpeechIndices = resolve(resolver, CHILD_SPEECH_CLASS_NAMES)
        whiteNoiseIndices = resolve(resolver, WHITE_NOISE_CLASS_NAMES)
        doorIndices = resolve(resolver, DOOR_CLASS_NAMES)
        dogBarkIndices = resolve(resolver, DOG_BARK_CLASS_NAMES)
        catIndices = resolve(resolver, CAT_CLASS_NAMES)
        birdIndices = resolve(resolver, BIRD_CLASS_NAMES)
        glassBreakIndices = resolve(resolver, GLASS_BREAK_CLASS_NAMES)
        sirenIndices = resolve(resolver, SIREN_CLASS_NAMES)
        phoneRingIndices = resolve(resolver, PHONE_RING_CLASS_NAMES)
        clapIndices = resolve(resolver, CLAP_CLASS_NAMES)
        whistleIndices = resolve(resolver, WHISTLE_CLASS_NAMES)
        footstepsIndices = resolve(resolver, FOOTSTEPS_CLASS_NAMES)
        waterIndices = resolve(resolver, WATER_CLASS_NAMES)
        musicIndices = resolve(resolver, MUSIC_CLASS_NAMES)
        pigIndices = resolve(resolver, PIG_CLASS_NAMES)
        cowIndices = resolve(resolver, COW_CLASS_NAMES)
        chickenIndices = resolve(resolver, CHICKEN_CLASS_NAMES)
        horseIndices = resolve(resolver, HORSE_CLASS_NAMES)
        sheepIndices = resolve(resolver, SHEEP_CLASS_NAMES)
        require(cryIndices.isNotEmpty() && intenseCryIndices.isNotEmpty()) {
            "YAMNet 模型缺少哭声类别（labels 不匹配）"
        }
        DebugLog.i(
            TAG,
            "YAMNet ready: input=${targetShape.contentToString()} output=${outputShape.contentToString()} " +
                "frameLayout=$frameLayout cry=${cryIndices.contentToString()} " +
                "intenseCry=${intenseCryIndices.contentToString()} speech=$speechIndex " +
                "cough=${coughIndices.contentToString()} sneeze=${sneezeIndices.contentToString()} " +
                "scream=${screamIndices.contentToString()} laughter=${laughterIndices.contentToString()} " +
                "childSpeech=${childSpeechIndices.contentToString()} " +
                "whiteNoise=${whiteNoiseIndices.contentToString()} door=${doorIndices.contentToString()} " +
                "dog=${dogBarkIndices.contentToString()} cat=${catIndices.contentToString()} " +
                "bird=${birdIndices.contentToString()} glass=${glassBreakIndices.contentToString()} " +
                "siren=${sirenIndices.contentToString()} phone=${phoneRingIndices.contentToString()} " +
                "clap=${clapIndices.contentToString()} whistle=${whistleIndices.contentToString()} " +
                "footsteps=${footstepsIndices.contentToString()} water=${waterIndices.contentToString()} " +
                "music=${musicIndices.contentToString()} pig=${pigIndices.contentToString()} " +
                "cow=${cowIndices.contentToString()} chicken=${chickenIndices.contentToString()} " +
                "horse=${horseIndices.contentToString()} sheep=${sheepIndices.contentToString()}",
        )
    }

    /** 把一组类名解析成索引；解析不到（labels 缺名且无 fallback）的类自动剔除。 */
    private fun resolve(resolver: LabelResolver, names: List<String>): IntArray =
        names.mapNotNull { resolver.indexOf(it) }.toIntArray()

    /**
     * 对一窗 16kHz 单声道 float PCM 做推理。
     *
     * @param samples 长度 >= [INPUT_SAMPLES]；不足时右侧补零。
     * @return 多类事件分数（各字段含义见 [YamnetScores]；同一帧上组内各类分数取逐帧
     *         最大，哭声与语音除外——哭声为组内求和、语音取单类值）。解码失败返回 null。
     */
    fun classify(samples: FloatArray): YamnetScores? {
        val input = padToWindow(samples)
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SAMPLES * 4)
            .order(ByteOrder.nativeOrder())
        for (v in input) inputBuffer.putFloat(v.coerceIn(-1f, 1f))
        inputBuffer.rewind()

        return try {
            if (frameLayout) {
                // 逐帧布局 [1, frames, 521]：按实际帧数分配输出，避免与张量形状不符。
                val frames = (interpreter.getOutputTensor(0).shape().getOrNull(1) ?: 1).coerceAtLeast(1)
                val out = Array(1) { Array(frames) { FloatArray(NUM_CLASSES) } }
                interpreter.run(inputBuffer, out)
                var peakCry = 0f
                var peakIntense = 0f
                var peakSpeech = 0f
                var peakCough = 0f
                var peakSneeze = 0f
                var peakScream = 0f
                var peakLaughter = 0f
                var peakChildSpeech = 0f
                var peakWhiteNoise = 0f
                var peakDoor = 0f
                var peakDogBark = 0f
                var peakCat = 0f
                var peakBird = 0f
                var peakGlassBreak = 0f
                var peakSiren = 0f
                var peakPhoneRing = 0f
                var peakClap = 0f
                var peakWhistle = 0f
                var peakFootsteps = 0f
                var peakWater = 0f
                var peakMusic = 0f
                var peakPig = 0f
                var peakCow = 0f
                var peakChicken = 0f
                var peakHorse = 0f
                var peakSheep = 0f
                for (frame in out[0]) {
                    val cry = cryIndices.sumOf { frame[it].toDouble() }.toFloat()
                    if (cry > peakCry) peakCry = cry
                    peakIntense = maxOf(peakIntense, groupMax(frame, intenseCryIndices))
                    if (speechIndex >= 0 && frame[speechIndex] > peakSpeech) {
                        peakSpeech = frame[speechIndex]
                    }
                    peakCough = maxOf(peakCough, groupMax(frame, coughIndices))
                    peakSneeze = maxOf(peakSneeze, groupMax(frame, sneezeIndices))
                    peakScream = maxOf(peakScream, groupMax(frame, screamIndices))
                    peakLaughter = maxOf(peakLaughter, groupMax(frame, laughterIndices))
                    peakChildSpeech = maxOf(peakChildSpeech, groupMax(frame, childSpeechIndices))
                    peakWhiteNoise = maxOf(peakWhiteNoise, groupMax(frame, whiteNoiseIndices))
                    peakDoor = maxOf(peakDoor, groupMax(frame, doorIndices))
                    peakDogBark = maxOf(peakDogBark, groupMax(frame, dogBarkIndices))
                    peakCat = maxOf(peakCat, groupMax(frame, catIndices))
                    peakBird = maxOf(peakBird, groupMax(frame, birdIndices))
                    peakGlassBreak = maxOf(peakGlassBreak, groupMax(frame, glassBreakIndices))
                    peakSiren = maxOf(peakSiren, groupMax(frame, sirenIndices))
                    peakPhoneRing = maxOf(peakPhoneRing, groupMax(frame, phoneRingIndices))
                    peakClap = maxOf(peakClap, groupMax(frame, clapIndices))
                    peakWhistle = maxOf(peakWhistle, groupMax(frame, whistleIndices))
                    peakFootsteps = maxOf(peakFootsteps, groupMax(frame, footstepsIndices))
                    peakWater = maxOf(peakWater, groupMax(frame, waterIndices))
                    peakMusic = maxOf(peakMusic, groupMax(frame, musicIndices))
                    peakPig = maxOf(peakPig, groupMax(frame, pigIndices))
                    peakCow = maxOf(peakCow, groupMax(frame, cowIndices))
                    peakChicken = maxOf(peakChicken, groupMax(frame, chickenIndices))
                    peakHorse = maxOf(peakHorse, groupMax(frame, horseIndices))
                    peakSheep = maxOf(peakSheep, groupMax(frame, sheepIndices))
                }
                YamnetScores(
                    cry = peakCry.coerceIn(0f, 1f),
                    intenseCry = peakIntense.coerceIn(0f, 1f),
                    speech = peakSpeech.coerceIn(0f, 1f),
                    cough = peakCough.coerceIn(0f, 1f),
                    sneeze = peakSneeze.coerceIn(0f, 1f),
                    scream = peakScream.coerceIn(0f, 1f),
                    laughter = peakLaughter.coerceIn(0f, 1f),
                    childSpeech = peakChildSpeech.coerceIn(0f, 1f),
                    whiteNoise = peakWhiteNoise.coerceIn(0f, 1f),
                    door = peakDoor.coerceIn(0f, 1f),
                    dogBark = peakDogBark.coerceIn(0f, 1f),
                    cat = peakCat.coerceIn(0f, 1f),
                    bird = peakBird.coerceIn(0f, 1f),
                    glassBreak = peakGlassBreak.coerceIn(0f, 1f),
                    siren = peakSiren.coerceIn(0f, 1f),
                    phoneRing = peakPhoneRing.coerceIn(0f, 1f),
                    clap = peakClap.coerceIn(0f, 1f),
                    whistle = peakWhistle.coerceIn(0f, 1f),
                    footsteps = peakFootsteps.coerceIn(0f, 1f),
                    water = peakWater.coerceIn(0f, 1f),
                    music = peakMusic.coerceIn(0f, 1f),
                    pig = peakPig.coerceIn(0f, 1f),
                    cow = peakCow.coerceIn(0f, 1f),
                    chicken = peakChicken.coerceIn(0f, 1f),
                    horse = peakHorse.coerceIn(0f, 1f),
                    sheep = peakSheep.coerceIn(0f, 1f),
                )
            } else {
                val out = Array(1) { FloatArray(NUM_CLASSES) }
                interpreter.run(inputBuffer, out)
                val row = out[0]
                YamnetScores(
                    cry = cryIndices.sumOf { row[it].toDouble() }.toFloat().coerceIn(0f, 1f),
                    intenseCry = groupMax(row, intenseCryIndices).coerceIn(0f, 1f),
                    speech = (if (speechIndex >= 0) row[speechIndex] else 0f).coerceIn(0f, 1f),
                    cough = groupMax(row, coughIndices).coerceIn(0f, 1f),
                    sneeze = groupMax(row, sneezeIndices).coerceIn(0f, 1f),
                    scream = groupMax(row, screamIndices).coerceIn(0f, 1f),
                    laughter = groupMax(row, laughterIndices).coerceIn(0f, 1f),
                    childSpeech = groupMax(row, childSpeechIndices).coerceIn(0f, 1f),
                    whiteNoise = groupMax(row, whiteNoiseIndices).coerceIn(0f, 1f),
                    door = groupMax(row, doorIndices).coerceIn(0f, 1f),
                    dogBark = groupMax(row, dogBarkIndices).coerceIn(0f, 1f),
                    cat = groupMax(row, catIndices).coerceIn(0f, 1f),
                    bird = groupMax(row, birdIndices).coerceIn(0f, 1f),
                    glassBreak = groupMax(row, glassBreakIndices).coerceIn(0f, 1f),
                    siren = groupMax(row, sirenIndices).coerceIn(0f, 1f),
                    phoneRing = groupMax(row, phoneRingIndices).coerceIn(0f, 1f),
                    clap = groupMax(row, clapIndices).coerceIn(0f, 1f),
                    whistle = groupMax(row, whistleIndices).coerceIn(0f, 1f),
                    footsteps = groupMax(row, footstepsIndices).coerceIn(0f, 1f),
                    water = groupMax(row, waterIndices).coerceIn(0f, 1f),
                    music = groupMax(row, musicIndices).coerceIn(0f, 1f),
                    pig = groupMax(row, pigIndices).coerceIn(0f, 1f),
                    cow = groupMax(row, cowIndices).coerceIn(0f, 1f),
                    chicken = groupMax(row, chickenIndices).coerceIn(0f, 1f),
                    horse = groupMax(row, horseIndices).coerceIn(0f, 1f),
                    sheep = groupMax(row, sheepIndices).coerceIn(0f, 1f),
                )
            }
        } catch (t: Throwable) {
            // Throwable 而非 Exception：原生层异常（UnsatisfiedLinkError 等）也绝不外逃，
            // 一律返回 null 让上层优雅降级，杜绝整个 App 闪退。
            DebugLog.e(TAG, "classify failed", t)
            null
        }
    }

    /** 组内各类的最大分数（该组可能为空集，此时返回 0f）。 */
    private fun groupMax(frame: FloatArray, indices: IntArray): Float {
        if (indices.isEmpty()) return 0f
        var best = 0f
        for (i in indices) if (frame[i] > best) best = frame[i]
        return best
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private fun padToWindow(samples: FloatArray): FloatArray {
        if (samples.size >= INPUT_SAMPLES) return samples.copyOf(INPUT_SAMPLES)
        return samples.copyOf(INPUT_SAMPLES)
    }

    private fun loadModel(context: Context, file: File): MappedByteBuffer =
        file.inputStream().channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())

    /**
     * 类别名解析：优先读模型旁的 `yamnet_labels.txt`（下载器一并落盘）；
     * 读不到时退回 TensorFlow Hub yamnet/1 的官方固定顺序（CSV class map），
     * 其中 Speech=0，三个哭声类按 AudioSet 本体顺序硬编码。
     */
    private inner class LabelResolver(private val labels: List<String>) {
        fun indexOf(name: String): Int? {
            val idx = labels.indexOf(name)
            if (idx >= 0) return idx
            return FALLBACK_INDEXES[name]
        }
    }

    private fun loadLabels(modelFile: File): List<String> {
        val labelFile = File(modelFile.parentFile, LABELS_FILE_NAME)
        return runCatching { labelFile.readLines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
    }
}

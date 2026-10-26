package com.sihoo.aemx.pipeline.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * STT 에 줄 핫워드 목록을 만든다. 한 줄에 "단어 :점수" 한 개, 줄바꿈으로 잇는다.
 *
 * 모델은 한국어 streaming zipformer transducer 이고 `modified_beam_search` + `modelingUnit = "cjkchar"`
 * 라서 한글을 그대로 적으면 글자 단위로 처리된다. 그래서 단어의 모든 글자가 모델 `tokens.txt` 에 있어야 한다.
 * 없는 글자가 섞인 핫워드는 인코딩에 실패하고, 실패했을 때의 동작(건너뜀인지 종료인지)이 sherpa-onnx
 * 버전마다 달라서 **미리 걸러 낸다**. 한글 음절과 공백만 받고 영문, 숫자, 기호는 버린다. 줄바꿈이나
 * ":" 가 섞이면 줄이 깨지므로 그것도 이 필터가 막는다.
 *
 * 점수는 보수적으로 잡는다. 핫워드가 너무 세면 비슷하게 들리는 엉뚱한 말이 이름으로 바뀐다.
 * 앱 이름과 연락처에는 기본 점수(2.0)보다 낮은 [DYNAMIC_SCORE] 를 쓴다.
 *
 * 속도: 핫워드가 늘면 beam search 가 핫워드 트라이를 같이 따라가므로 디코딩이 조금 느려질 수 있다.
 * 그래서 연락처는 [MAX_CONTACTS] 개, 앱 라벨은 [MAX_APP_LABELS] 개로 막는다. 폰에서 측정은 메인이 한다.
 */
internal object HotwordBuilder {

    /** 앱 라벨과 연락처 이름에 줄 점수. 고정 단어의 기본값(2.0)보다 낮다. */
    const val DYNAMIC_SCORE = 1.5f

    const val MAX_CONTACTS = 200
    const val MAX_APP_LABELS = 100

    private const val MIN_LETTERS = 2
    private const val MAX_LETTERS = 10

    /**
     * @param fixed 이미 "단어 :점수" 꼴인 고정 핫워드(앱 이름, 명령어, 호출어). 그대로 앞에 둔다.
     * @param isKnownChar 모델 tokens.txt 에 있는 글자인지.
     */
    fun build(
        fixed: List<String>,
        appLabels: List<String>,
        contactNames: List<String>,
        isKnownChar: (Char) -> Boolean,
    ): String {
        val seen = fixed.map { key(it.substringBefore(':')) }.toMutableSet()
        val lines = fixed.toMutableList()
        fun addAll(names: List<String>, limit: Int) {
            var added = 0
            for (name in names) {
                if (added >= limit) break
                val word = clean(name, isKnownChar) ?: continue
                if (!seen.add(key(word))) continue
                lines += String.format(Locale.ROOT, "%s :%.1f", word, DYNAMIC_SCORE)
                added++
            }
        }
        addAll(appLabels, MAX_APP_LABELS)
        addAll(contactNames, MAX_CONTACTS)
        return lines.joinToString("\n")
    }

    /** 핫워드로 써도 되는 이름이면 공백을 하나로 줄인 이름, 아니면 null. */
    internal fun clean(raw: String, isKnownChar: (Char) -> Boolean): String? {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        val letters = name.filterNot { it == ' ' }
        if (letters.length !in MIN_LETTERS..MAX_LETTERS) return null
        if (!letters.all { it in '가'..'힣' && isKnownChar(it) }) return null
        return name
    }

    private fun key(word: String): String = word.filterNot { it.isWhitespace() }

    /** 모델 tokens.txt 의 한 글자 토큰들. 읽지 못하면 비어 있어 동적 핫워드가 모두 걸러진다. */
    fun knownChars(context: Context, tokensAsset: String): Set<Char> = runCatching {
        context.assets.open(tokensAsset).bufferedReader().useLines { lines ->
            lines.mapNotNull { it.substringBeforeLast(' ').singleOrNull() }.toSet()
        }
    }.getOrElse {
        Log.w(TAG, "tokens.txt 를 읽지 못해 앱 이름과 연락처 핫워드를 쓰지 않는다", it)
        emptySet()
    }

    /** 설치된 런처 앱의 라벨. 런처 액티비티가 있는 앱만 본다. */
    fun launcherLabels(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() }.distinct()
    }.getOrElse { emptyList() }

    /** 연락처 이름. READ_CONTACTS 가 없으면 비어 있다. 즐겨찾기와 자주 연락한 사람을 앞에 둔다. */
    fun contactNames(context: Context): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return runCatching {
            val names = mutableListOf<String>()
            @Suppress("DEPRECATION")
            val order = "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.TIMES_CONTACTED} DESC"
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null, order,
            )?.use { cursor ->
                while (cursor.moveToNext() && names.size < MAX_CONTACTS * 3) {
                    cursor.getString(0)?.let(names::add)
                }
            }
            names
        }.getOrElse {
            Log.w(TAG, "연락처를 읽지 못했다", it)
            emptyList()
        }
    }

    private const val TAG = "HotwordBuilder"
}

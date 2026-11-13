package com.teclado.inteligente

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

class TecladoService : InputMethodService() {

    private var palavras: List<String> = emptyList()
    private var normalizadas: List<String> = emptyList()
    private var conjunto: HashSet<String> = HashSet()
    private val buffer = StringBuilder()
    private var maiuscula = false
    private var ativo = true
    private var palavraErro: String? = null
    private var correcaoErro: String? = null
    private var extrasVisiveis = false
    private var emojiVisivel = false
    private val barra = ArrayList<TextView>()
    private val letras = ArrayList<Button>()
    private var linhaExtras: LinearLayout? = null
    private var linhaEmoji: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        palavras = assets.open("palavras.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        normalizadas = palavras.map { sem(it) }
        conjunto = palavras.map { it.lowercase() }.toHashSet()
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        buffer.clear()
        palavraErro = null
        correcaoErro = null
        val tipo = info?.inputType ?: 0
        val classe = tipo and InputType.TYPE_MASK_CLASS
        val v = tipo and InputType.TYPE_MASK_VARIATION
        ativo = classe == InputType.TYPE_CLASS_TEXT &&
            v != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_URI
        atualizarSugestoes()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sem(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun dist(a: String, b: String): Int {
        var ant = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val atual = IntArray(b.length + 1)
            atual[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                atual[j] = min(min(atual[j - 1] + 1, ant[j] + 1), ant[j - 1] + c)
            }
            ant = atual
        }
        return ant[b.length]
    }

    private fun calcular(parcial: String): List<String> {
        if (parcial.isEmpty()) return emptyList()
        val p = sem(parcial)
        val res = ArrayList<String>()
        for (i in palavras.indices) {
            if (normalizadas[i].startsWith(p)) {
                res.add(palavras[i]); if (res.size >= 3) break
            }
        }
        if (res.size < 3) {
            val cand = ArrayList<Pair<Int, Int>>()
            for (i in palavras.indices) {
                val n = normalizadas[i]
                if (abs(n.length - p.length) > 2) continue
                val d = dist(p, n)
                if (d <= 2) cand.add(Pair(d, i))
            }
            cand.sortWith(compareBy({ it.first }, { it.second }))
            for (c in cand) {
                val w = palavras[c.second]
                if (w !in res) { res.add(w); if (res.size >= 3) break }
            }
        }
        return res
    }

    private fun melhorCorrecao(w: String): String? {
        val p = sem(w)
        if (p.length < 3) return null
        val limite = if (p.length <= 4) 1 else 2
        var melhorD = 99
        var melhorI = -1
        for (i in palavras.indices) {
            val n = normalizadas[i]
            if (abs(n.length - p.length) > limite) continue
            val d = dist(p, n)
            if (d <= limite && d < melhorD) { melhorD = d; melhorI = i }
        }
        return if (melhorI >= 0) palavras[melhorI] else null
    }

    private fun comCase(original: String, nova: String): String =
        if (original.isNotEmpty() && original[0].isUpperCase())
            nova.replaceFirstChar { it.uppercase() } else nova

    // Mantém a palavra digitada e oferece a correção; não troca o texto sem autorização.
    private fun fechar() {
        val w = buffer.toString()
        buffer.setLength(0)
        if (w.isNotEmpty() && ativo && !conjunto.contains(w.lowercase())) {
            val corr = melhorCorrecao(w)
            if (corr != null) {
                palavraErro = w
                correcaoErro = comCase(w, corr)
            } else {
                palavraErro = null
                correcaoErro = null
            }
        } else {
            palavraErro = null
            correcaoErro = null
        }
        atualizarSugestoes()
    }

    private fun atualizarSugestoes() {
        val erro = if (buffer.isEmpty()) correcaoErro else null
        val lista = if (!ativo) emptyList() else if (erro != null) {
            listOf(erro, "Manter", "")
        } else calcular(buffer.toString())
        for (i in barra.indices) {
            val t = barra[i]
            t.text = if (i < lista.size) {
                if (erro != null && i == 0) "✓ $erro" else lista[i]
            } else ""
            t.setTextColor(
                if (erro != null && i == 0) Color.rgb(190, 35, 45)
                else Color.rgb(35, 43, 54)
            )
            t.tag = if (erro != null && i == 0) "corrigir" else if (erro != null && i == 1) "manter" else "sugestao"
        }
    }

    private fun aplicarSugestao(t: TextView) {
        val ic = currentInputConnection ?: return
        if (t.tag == "manter") {
            palavraErro = null
            correcaoErro = null
            atualizarSugestoes()
            return
        }
        if (t.tag == "corrigir") {
            val original = palavraErro ?: return
            val correcao = correcaoErro ?: return
            val antes = ic.getTextBeforeCursor(original.length + 1, 0)?.toString() ?: return
            val pontuacao = if (antes.length > original.length &&
                !antes.last().isLetterOrDigit() && !antes.last().isWhitespace()) antes.last().toString() else ""
            val sufixo = original + pontuacao
            if (!antes.endsWith(sufixo, ignoreCase = true)) return
            ic.deleteSurroundingText(sufixo.length, 0)
            ic.commitText(correcao + pontuacao, 1)
            palavraErro = null
            correcaoErro = null
            atualizarSugestoes()
            return
        }
        val s = t.text.toString()
        val atual = buffer.toString()
        if (s.isBlank() || atual.isBlank()) return
        val antes = ic.getTextBeforeCursor(atual.length, 0)?.toString()
        if (antes != atual) return
        ic.deleteSurroundingText(atual.length, 0)
        ic.commitText("$s ", 1)
        buffer.setLength(0)
        atualizarSugestoes()
    }

    private fun digitarLetra(l: String) {
        palavraErro = null
        correcaoErro = null
        val c = if (maiuscula) l.uppercase() else l
        currentInputConnection?.commitText(c, 1)
        buffer.append(c)
        if (maiuscula) { maiuscula = false; atualizarCase() }
        atualizarSugestoes()
    }

    private fun digitarSimbolo(s: String) {
        val ic = currentInputConnection ?: return
        if (s == " " && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            buffer.setLength(0)
            palavraErro = null
            correcaoErro = null
            maiuscula = true
            atualizarCase()
            atualizarSugestoes()
            return
        }
        fechar()
        ic.commitText(s, 1)
        if (s == "." || s == "!" || s == "?") {
            maiuscula = true
            atualizarCase()
        }
    }

    private fun apagar() {
        if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
        palavraErro = null
        correcaoErro = null
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        atualizarSugestoes()
    }

    private fun enter() {
        fechar()
        val ei = currentInputEditorInfo
        val acao = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val semAcao = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!semAcao && acao != EditorInfo.IME_ACTION_NONE && acao != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(acao)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun atualizarCase() {
        for (b in letras) {
            val l = b.tag as String
            b.text = if (maiuscula) l.uppercase() else l
        }
    }

    private fun tecla(texto: String, peso: Float, especial: Boolean = false, acao: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.isAllCaps = false
        b.minWidth = 0
        b.minHeight = 0
        b.textSize = if (texto == "espaço") 14f else 18f
        b.setTextColor(if (especial) Color.rgb(57, 67, 82) else Color.rgb(31, 39, 50))
        b.setPadding(0, dp(10), 0, dp(10))
        val fundo = GradientDrawable()
        fundo.cornerRadius = dp(9).toFloat()
        fundo.setColor(if (especial) Color.rgb(226, 231, 238) else Color.WHITE)
        fundo.setStroke(dp(1), Color.rgb(222, 227, 234))
        b.background = fundo
        b.elevation = dp(1).toFloat()
        b.setOnClickListener { acao() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, peso)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        b.layoutParams = lp
        return b
    }

    private fun linha(raiz: LinearLayout): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        raiz.addView(l, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return l
    }

    private fun ferramenta(icone: String, dica: String, acao: () -> Unit): TextView {
        val t = TextView(this)
        t.text = icone
        t.contentDescription = dica
        t.gravity = Gravity.CENTER
        t.textSize = 22f
        t.setTextColor(Color.rgb(65, 76, 92))
        t.setBackground(makeRound(Color.rgb(245, 247, 250), 12))
        t.setOnClickListener { acao() }
        val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
        lp.setMargins(dp(3), dp(2), dp(3), dp(2))
        t.layoutParams = lp
        return t
    }

    private fun makeRound(cor: Int, raio: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(raio).toFloat()
            setColor(cor)
        }

    private fun aviso(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun capturarTela() {
        aviso("Para capturar a tela, use os botões Liga/Desliga + Volume para baixo do celular. O Android exige autorização própria para captura por aplicativos.")
    }

    private fun iniciarVoz() {
        try {
            val retorno = Intent(this, SpeechReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(this, 8127, retorno, flags)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale para escrever")
                putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pending)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            aviso("Não encontrei um serviço de reconhecimento de voz instalado no celular.")
        }
    }

    private fun abrirVideos() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            aviso("Não foi possível abrir a galeria de vídeos.")
        }
    }

    private fun colarAreaTransferencia() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val texto = if (cm.hasPrimaryClip()) cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() else null
        if (!texto.isNullOrEmpty()) currentInputConnection?.commitText(texto, 1)
        else aviso("A área de transferência está vazia.")
    }

    private fun inserirEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
    }

    override fun onCreateInputView(): View {
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(Color.rgb(239, 242, 247))
        raiz.setPadding(dp(4), dp(4), dp(4), dp(6))

        val topo = linha(raiz)
        topo.addView(ferramenta("▣", "Captura de tela") { capturarTela() })
        topo.addView(ferramenta("🎙", "Digitação por voz") { iniciarVoz() })
        topo.addView(ferramenta("☺", "Emojis") {
            emojiVisivel = !emojiVisivel
            linhaEmoji?.visibility = if (emojiVisivel) View.VISIBLE else View.GONE
        })
        topo.addView(ferramenta("▶", "Vídeos") { abrirVideos() })
        topo.addView(ferramenta("⋯", "Mais ferramentas") {
            extrasVisiveis = !extrasVisiveis
            linhaExtras?.visibility = if (extrasVisiveis) View.VISIBLE else View.GONE
        })

        linhaExtras = linha(raiz).apply {
            visibility = View.GONE
            addView(ferramenta("📋", "Área de transferência") { colarAreaTransferencia() })
            addView(ferramenta("🌐", "Tradução") { aviso("A ferramenta de tradução será adicionada em uma próxima atualização.") })
            addView(ferramenta("⚙", "Configurações") { aviso("Configurações do XFLUX Teclado: em preparação.") })
        }

        linhaEmoji = linha(raiz).apply {
            visibility = View.GONE
            listOf("😀", "😂", "😍", "🥰", "👍", "❤️", "🙏", "🔥", "🎉", "😊").forEach { emoji ->
                val t = TextView(this@TecladoService)
                t.text = emoji
                t.textSize = 24f
                t.gravity = Gravity.CENTER
                t.setOnClickListener { inserirEmoji(emoji) }
                addView(t, LinearLayout.LayoutParams(0, dp(42), 1f))
            }
        }

        val sugestoes = linha(raiz)
        sugestoes.setPadding(dp(2), dp(2), dp(2), dp(2))
        barra.clear()
        repeat(3) {
            val t = TextView(this)
            t.gravity = Gravity.CENTER
            t.setTextColor(Color.rgb(35, 43, 54))
            t.textSize = 15f
            t.setPadding(dp(4), dp(8), dp(4), dp(8))
            t.setOnClickListener { aplicarSugestao(t) }
            sugestoes.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            barra.add(t)
        }

        letras.clear()
        val l1 = linha(raiz)
        "1234567890".forEach { c ->
            l1.addView(tecla(c.toString(), 1f, true) { digitarLetra(c.toString()) })
        }

        val l2 = linha(raiz)
        "qwertyuiop".forEach { c ->
            val k = tecla(c.toString(), 1f) { digitarLetra(c.toString()) }
            k.tag = c.toString(); letras.add(k); l2.addView(k)
        }

        val l3 = linha(raiz)
        "asdfghjklç".forEach { c ->
            val k = tecla(c.toString(), 1f) { digitarLetra(c.toString()) }
            k.tag = c.toString(); letras.add(k); l3.addView(k)
        }

        val l4 = linha(raiz)
        l4.addView(tecla("⇧", 1.4f, true) { maiuscula = !maiuscula; atualizarCase() })
        "zxcvbnm".forEach { c ->
            val k = tecla(c.toString(), 1f) { digitarLetra(c.toString()) }
            k.tag = c.toString(); letras.add(k); l4.addView(k)
        }
        l4.addView(tecla("⌫", 1.4f, true) { apagar() })

        val l5 = linha(raiz)
        l5.addView(tecla(",", 1f, true) { digitarSimbolo(",") })
        l5.addView(tecla("espaço", 5f) { digitarSimbolo(" ") })
        l5.addView(tecla(".", 1f, true) { digitarSimbolo(".") })
        l5.addView(tecla("↵", 1.6f, true) { enter() })

        atualizarSugestoes()
        return raiz
    }

    companion object {
        @Volatile var instancia: TecladoService? = null
    }
}

class SpeechReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val resultado = intent.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!resultado.isNullOrBlank()) {
            TecladoService.instancia?.let { service ->
                service.currentInputConnection?.commitText("$resultado ", 1)
            }
        }
    }
}

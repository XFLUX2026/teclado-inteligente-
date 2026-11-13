package com.teclado.inteligente

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private val barra = ArrayList<TextView>()
    private val letras = ArrayList<Button>()

    override fun onCreate() {
        super.onCreate()
        palavras = assets.open("palavras.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        normalizadas = palavras.map { sem(it) }
        conjunto = palavras.map { it.lowercase() }.toHashSet()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        buffer.clear()
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

    private fun fechar() {
        val ic = currentInputConnection ?: return
        val w = buffer.toString()
        buffer.setLength(0)
        if (w.isEmpty() || !ativo) { atualizarSugestoes(); return }
        if (!conjunto.contains(w.lowercase())) {
            val corr = melhorCorrecao(w)
            val antes = ic.getTextBeforeCursor(w.length, 0)?.toString()
            if (corr != null && antes == w) {
                ic.deleteSurroundingText(w.length, 0)
                ic.commitText(comCase(w, corr), 1)
            }
        }
        atualizarSugestoes()
    }

    private fun atualizarSugestoes() {
        val lista = if (ativo) calcular(buffer.toString()) else emptyList()
        for (i in barra.indices) {
            barra[i].text = if (i < lista.size) comCase(buffer.toString(), lista[i]) else ""
        }
    }

    private fun aplicarSugestao(t: TextView) {
        val ic = currentInputConnection ?: return
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

    private fun tecla(texto: String, peso: Float, cor: String, acao: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.isAllCaps = false
        b.minWidth = 0
        b.minHeight = 0
        b.textSize = 18f
        b.setTextColor(Color.WHITE)
        b.setPadding(0, dp(12), 0, dp(12))
        val fundo = GradientDrawable()
        fundo.cornerRadius = dp(8).toFloat()
        fundo.setColor(Color.parseColor(cor))
        b.background = fundo
        b.setOnClickListener { acao() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, peso)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        b.layoutParams = lp
        return b
    }

    private fun linha(raiz: LinearLayout): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        raiz.addView(l, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return l
    }

    override fun onCreateInputView(): View {
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(Color.parseColor("#1B1B1F"))
        raiz.setPadding(dp(4), dp(4), dp(4), dp(6))

        val b = linha(raiz)
        barra.clear()
        repeat(3) {
            val t = TextView(this)
            t.gravity = Gravity.CENTER
            t.setTextColor(Color.WHITE)
            t.textSize = 17f
            t.setPadding(dp(4), dp(10), dp(4), dp(10))
            t.setOnClickListener { aplicarSugestao(t) }
            b.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            barra.add(t)
        }

        letras.clear()
        val corLetra = "#3A3A42"
        val corEspecial = "#2A2A30"

        val l1 = linha(raiz)
        "1234567890".forEach { c -> l1.addView(tecla(c.toString(), 1f, corEspecial) { digitarLetra(c.toString()) }) }

        val l2 = linha(raiz)
        "qwertyuiop".forEach { c ->
            val k = tecla(c.toString(), 1f, corLetra) { digitarLetra(c.toString()) }
            k.tag = c.toString(); letras.add(k); l2.addView(k)
        }

        val l3 = linha(raiz)
        "asdfghjklç".forEach { c ->
            val k = tecla(c.toString(), 1f, corLetra) { digitarLetra(c.toString()) }
            k.tag = c.toString(); letras.add(k); l3.addView(k)
        }

        val l4 = linha(raiz)
        l4.addView(tecla("⇧", 1.4f, corEspecial) { maiuscula = !maiuscula; atualizarCase() })
        "zxcvbnm".forEach { c ->
            val k = tecla(c.toString(), 1f, corLetra) { digitarLetra(c.toString()) }
            k.tag = c.toString(); letras.add(k); l4.addView(k)
        }
        l4.addView(tecla("⌫", 1.4f, corEspecial) { apagar() })

        val l5 = linha(raiz)
        l5.addView(tecla(",", 1f, corEspecial) { digitarSimbolo(",") })
        l5.addView(tecla("espaço", 5f, corLetra) { digitarSimbolo(" ") })
        l5.addView(tecla(".", 1f, corEspecial) { digitarSimbolo(".") })
        l5.addView(tecla("↵", 1.6f, "#1565C0") { enter() })

        atualizarSugestoes()
        return raiz
    }
}

package com.example.remindersms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.DecimalFormat

data class Customer(
    val name: String,
    val phone: String,
    var credit: Double = 0.0, // له عندنا
    var debt: Double = 0.0    // عليه لنا
) {
    val netDebt: Double get() = debt - credit
}

class MainActivity : Activity() {
    private val customers = mutableListOf<Customer>()
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var filterDebt: CheckBox
    private val money = DecimalFormat("#,##0.##")
    private var pendingBulk = false

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importCsv(uri)
    }

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingBulk) sendDebtMessages()
        else if (!granted) toast("لم يتم منح إذن إرسال الرسائل")
        pendingBulk = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF7F9FC.toInt())
        }
        val title = TextView(this).apply {
            text = "رسائل مديونية العملاء"
            textSize = 24f
            setTextColor(0xFF123047.toInt())
        }
        summary = TextView(this).apply { textSize = 15f; setPadding(0, 10, 0, 12) }
        val import = Button(this).apply {
            text = "استيراد ملف CSV"
            setOnClickListener { importFile.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }
        }
        val add = Button(this).apply {
            text = "إضافة حركة (له / عليه)"
            setOnClickListener { showAddTransactionDialog() }
        }
        val send = Button(this).apply {
            text = "إرسال تذكير لكل المدينين"
            setOnClickListener { confirmBulkSend() }
        }
        filterDebt = CheckBox(this).apply {
            text = "عرض العملاء الذين عليهم مديونية فقط"
            isChecked = false
            setOnCheckedChangeListener { _, _ -> refreshList() }
        }
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(title)
        root.addView(summary)
        root.addView(import)
        root.addView(add)
        root.addView(send)
        root.addView(filterDebt)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun importCsv(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: throw Exception("تعذر فتح الملف")
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val lines = reader.readLines()
            reader.close()
            if (lines.isEmpty()) throw Exception("الملف فارغ")
            val delimiter = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
            val header = splitCsv(lines.first(), delimiter).map { norm(it) }
            val nameIdx = findHeader(header, listOf("الاسم", "اسم العميل", "name", "customer"))
            val phoneIdx = findHeader(header, listOf("الهاتف", "رقم الهاتف", "الجوال", "الرقم", "phone", "mobile"))
            val creditIdx = findHeader(header, listOf("له", "رصيد له", "دائن", "credit", "لنا عليه"))
            val debtIdx = findHeader(header, listOf("عليه", "رصيد عليه", "مدين", "debt", "مديونية"))
            val balanceIdx = findHeader(header, listOf("الرصيد", "الرصيد النهائي", "balance"))
            if (nameIdx < 0 || phoneIdx < 0) {
                throw Exception("يجب أن يحتوي الملف على عمود الاسم وعمود الهاتف. صدّر الملف بصيغة CSV.")
            }
            var count = 0
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val cols = splitCsv(line, delimiter)
                fun col(i: Int) = if (i >= 0 && i < cols.size) cols[i].trim() else ""
                val name = col(nameIdx)
                val phone = col(phoneIdx).replace(" ", "").replace("-", "")
                if (name.isBlank() || phone.isBlank()) continue
                val credit = parseAmount(col(creditIdx))
                var debt = parseAmount(col(debtIdx))
                if (balanceIdx >= 0 && creditIdx < 0 && debtIdx < 0) {
                    val bal = parseAmount(col(balanceIdx))
                    if (bal >= 0) debt = bal
                }
                val existing = customers.indexOfFirst { it.phone == phone }
                val customer = Customer(name, phone, credit, debt)
                if (existing >= 0) customers[existing] = customer else customers.add(customer)
                count++
            }
            refresh()
            toast("تم استيراد/تحديث $count عميل")
        } catch (e: Exception) {
            toast("تعذر الاستيراد: ${e.message ?: "تحقق من تنسيق CSV"}")
        }
    }

    private fun splitCsv(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                    cell.append('"'); i++
                } else quoted = !quoted
            } else if (c == delimiter && !quoted) {
                out.add(cell.toString()); cell.setLength(0)
            } else cell.append(c)
            i++
        }
        out.add(cell.toString())
        return out
    }

    private fun norm(s: String) = s.trim().lowercase().replace("ـ", "").replace(" ", "")
    private fun findHeader(headers: List<String>, candidates: List<String>): Int {
        return headers.indexOfFirst { h -> candidates.any { norm(it) == h || h.contains(norm(it)) } }
    }
    private fun parseAmount(s: String): Double =
        s.trim().replace(",", "").replace("٬", "").replace("٫", ".")
            .replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: 0.0

    private fun showAddTransactionDialog() {
        if (customers.isEmpty()) { toast("استورد ملف العملاء أولاً"); return }
        val names = customers.map { "${it.name} — ${it.phone}" }.toTypedArray()
        val picker = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names) }
        val amount = EditText(this).apply { hint = "المبلغ"; inputType = 8194 }
        val type = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply { text = "مبلغ عليه العميل (مديونية)"; id = 1; isChecked = true })
            addView(RadioButton(this@MainActivity).apply { text = "مبلغ له عندنا (رصيد للعميل)"; id = 2 })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
            addView(picker); addView(amount); addView(type)
        }
        AlertDialog.Builder(this).setTitle("إضافة حركة").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val value = amount.text.toString().toDoubleOrNull()
                if (value == null || value <= 0) { toast("أدخل مبلغاً صحيحاً"); return@setPositiveButton }
                val c = customers[picker.selectedItemPosition]
                if (type.checkedRadioButtonId == 1) c.debt += value else c.credit += value
                refresh()
                toast("تم تحديث رصيد ${c.name}")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun confirmBulkSend() {
        val debtors = customers.filter { it.netDebt > 0 && it.phone.isNotBlank() }
        if (debtors.isEmpty()) { toast("لا يوجد عملاء عليهم مديونية"); return }
        val message = "سيتم تجهيز ${debtors.size} رسالة SMS، وقد تُحسب رسوم حسب شركة الاتصالات. راجع الأرقام والأرصدة قبل الإرسال."
        AlertDialog.Builder(this).setTitle("تأكيد إرسال الرسائل")
            .setMessage(message)
            .setPositiveButton("متابعة") { _, _ ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    sendDebtMessages()
                } else {
                    pendingBulk = true
                    smsPermission.launch(Manifest.permission.SEND_SMS)
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun sendDebtMessages() {
        val debtors = customers.filter { it.netDebt > 0 && it.phone.isNotBlank() }
        if (debtors.isEmpty()) return
        AlertDialog.Builder(this).setTitle("إرسال ${debtors.size} رسالة؟")
            .setMessage("سيتم إرسال رسالة منفصلة لكل عميل مدين. تأكد من صحة البيانات وموافقة العملاء على تلقي التذكيرات.")
            .setPositiveButton("إرسال الآن") { _, _ ->
                try {
                    @Suppress("DEPRECATION")
                    val sms = SmsManager.getDefault()
                    var sent = 0
                    debtors.forEach { c ->
                        val body = "مرحباً ${c.name}، نذكّرك بأن الرصيد المستحق عليك هو ${money.format(c.netDebt)}. إذا كنت قد سددت المبلغ، يرجى تجاهل الرسالة. شكراً لك."
                        val parts = sms.divideMessage(body)
                        sms.sendMultipartTextMessage(c.phone, null, parts, null, null)
                        sent++
                    }
                    toast("تمت محاولة إرسال $sent رسالة. تحقق من تطبيق الرسائل وسجل الإرسال.")
                } catch (e: Exception) {
                    toast("تعذر إرسال الرسائل: ${e.message ?: "تحقق من الشريحة والأذونات"}")
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun refresh() {
        val totalDebt = customers.sumOf { if (it.netDebt > 0) it.netDebt else 0.0 }
        val debtors = customers.count { it.netDebt > 0 }
        summary.text = "عدد العملاء: ${customers.size}    |    المدينون: $debtors\nإجمالي المديونية: ${money.format(totalDebt)}"
        refreshList()
    }

    private fun refreshList() {
        list.removeAllViews()
        val shown = customers.filter { !filterDebt.isChecked || it.netDebt > 0 }
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (customers.isEmpty()) "ابدأ باستيراد ملف CSV من تطبيق الحسابات." else "لا يوجد عملاء مطابقون."
                textSize = 16f; setPadding(8, 24, 8, 24)
            })
        }
        shown.forEach { c ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            card.addView(TextView(this).apply { text = c.name; textSize = 18f; setTextColor(0xFF123047.toInt()) })
            card.addView(TextView(this).apply { text = "الهاتف: ${c.phone}\nله عندنا: ${money.format(c.credit)}    |    عليه: ${money.format(c.debt)}\nالصافي: ${money.format(c.netDebt)}"; textSize = 14f })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this@MainActivity).apply {
                text = "إضافة حركة"
                setOnClickListener { showAddTransactionFor(c) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this@MainActivity).apply {
                text = "إرسال تذكير"
                setOnClickListener { sendOne(c) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(actions)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = 12
            list.addView(card, lp)
        }
    }

    private fun showAddTransactionFor(c: Customer) {
        val amount = EditText(this).apply { hint = "المبلغ"; inputType = 8194 }
        val type = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply { text = "عليه العميل (مديونية)"; id = 1; isChecked = true })
            addView(RadioButton(this@MainActivity).apply { text = "له عندنا"; id = 2 })
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8); addView(amount); addView(type) }
        AlertDialog.Builder(this).setTitle("حركة: ${c.name}").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val v = amount.text.toString().toDoubleOrNull()
                if (v == null || v <= 0) { toast("أدخل مبلغاً صحيحاً"); return@setPositiveButton }
                if (type.checkedRadioButtonId == 1) c.debt += v else c.credit += v
                refresh()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun sendOne(c: Customer) {
        if (c.netDebt <= 0) { toast("هذا العميل ليس عليه رصيد مستحق"); return }
        AlertDialog.Builder(this).setTitle("إرسال تذكير")
            .setMessage("إلى: ${c.name}\n${c.phone}\nالمبلغ: ${money.format(c.netDebt)}")
            .setPositiveButton("إرسال") { _, _ ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    pendingBulk = false
                    smsPermission.launch(Manifest.permission.SEND_SMS)
                    toast("امنح الإذن ثم اضغط إرسال تذكير مرة أخرى")
                    return@setPositiveButton
                }
                try {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault().sendTextMessage(
                        c.phone, null,
                        "مرحباً ${c.name}، نذكّرك بأن الرصيد المستحق عليك هو ${money.format(c.netDebt)}. إذا كنت قد سددت المبلغ، يرجى تجاهل الرسالة. شكراً لك.",
                        null, null
                    )
                    toast("تمت محاولة إرسال الرسالة")
                } catch (e: Exception) { toast("تعذر الإرسال: ${e.message}") }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

package com.prozo.putaway
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
 private val PICK=42
 private var values= mutableListOf<String>()
 private lateinit var status:TextView
 private lateinit var link:EditText
 private lateinit var delay:EditText
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val box=LinearLayout(this).apply { orientation=1; setPadding(26,26,26,24) }
  box.addView(TextView(this).apply { text="Unicommerce Putaway Assistant"; textSize=23f })
  box.addView(TextView(this).apply { text="Google Sheets: share as Anyone with the link — Viewer." })
  link=EditText(this).apply { hint="Paste Google Sheets link"; setSingleLine(true) }; box.addView(link)
  box.addView(Button(this).apply { text="1. SYNC GOOGLE SHEET"; setOnClickListener { sync() } })
  box.addView(TextView(this).apply { text="Reads Column A, skips row 1 header, and treats every remaining non-empty value as a barcode. No shelf code." })
  box.addView(Button(this).apply { text="Or select Excel / CSV backup"; setOnClickListener {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*"; putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv")) },PICK)
  }})
  delay=EditText(this).apply { hint="Delay in milliseconds"; setText("2000"); inputType=2 }; box.addView(delay)
  box.addView(Button(this).apply { text="2. ENABLE ACCESSIBILITY"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
  box.addView(Button(this).apply { text="3. START (open Unicommerce and focus scan field first)"; setOnClickListener {
   if(values.isEmpty()) status.text="Sync a sheet or select a backup file first."
   else { PutawayAccessibilityService.begin(values,delay.text.toString().toLongOrNull()?.coerceIn(1000,10000)?:2000L); status.text="Started ${values.size} barcode rows. Switch to Unicommerce and keep scan field focused." }
  }})
  box.addView(Button(this).apply { text="Pause / Resume"; setOnClickListener { PutawayAccessibilityService.togglePause(); status.text="Pause/resume requested." } })
  box.addView(Button(this).apply { text="Stop"; setOnClickListener { PutawayAccessibilityService.stopRun(); status.text="Stopped." } })
  status=TextView(this).apply { text="No data loaded."; textSize=16f }; box.addView(status)
  box.addView(TextView(this).apply { text="Test with one row first. The app never taps Close Putaway. A sent value is not proof of server acceptance." })
  setContentView(ScrollView(this).apply { addView(box) })
 }
 private fun sync() {
  val raw=link.text.toString().trim()
  val id=Regex("/spreadsheets/d/([A-Za-z0-9_-]+)").find(raw)?.groupValues?.get(1)
  val gid=Regex("[?&#]gid=([0-9]+)").find(raw)?.groupValues?.get(1) ?: "0"
  if(id==null) { status.text="Invalid Google Sheets URL."; return }
  status.text="Connecting to Google Sheets..."
  thread {
   try {
    val u=URL("https://docs.google.com/spreadsheets/d/$id/export?format=csv&gid=$gid")
    val c=u.openConnection() as HttpURLConnection
    c.connectTimeout=15000; c.readTimeout=20000; c.setRequestProperty("User-Agent","PutawayAssistant/2.0")
    val code=c.responseCode
    if(code!=200) throw Exception("HTTP $code. Check Anyone with the link → Viewer.")
    val csv=BufferedReader(InputStreamReader(c.inputStream,Charsets.UTF_8)).use { it.readText() }; c.disconnect()
    val rows=parseFirstColumn(csv).drop(1).map { it.trim() }.filter { it.isNotEmpty() }
    if(rows.isEmpty()) throw Exception("No barcode rows found in Column A after header.")
    runOnUiThread { values=rows.toMutableList(); status.text="Synced: ${values.size} barcode rows. Header skipped; shelf code disabled." }
   } catch(e:Exception) { runOnUiThread { status.text="Sync failed: ${e.message}. Check link sharing and worksheet gid." } }
  }
 }
 private fun parseFirstColumn(s:String):List<String> {
  val out=mutableListOf<String>(); val f=StringBuilder(); var quoted=false; var first=true; var firstVal=""
  var i=0
  while(i<s.length) { val ch=s[i]
   when {
    ch=='"' && quoted && i+1<s.length && s[i+1]=='"' -> { f.append('"'); i++ }
    ch=='"' -> quoted=!quoted
    ch==',' && !quoted -> { if(first){firstVal=f.toString();first=false}; f.setLength(0) }
    (ch=='\n'||ch=='\r') && !quoted -> { if(first) firstVal=f.toString(); out.add(firstVal.removePrefix("\uFEFF")); f.setLength(0); firstVal=""; first=true; if(ch=='\r'&&i+1<s.length&&s[i+1]=='\n') i++ }
    else -> f.append(ch)
   }; i++
  }
  if(f.isNotEmpty()||firstVal.isNotEmpty()) { if(first) firstVal=f.toString(); out.add(firstVal.removePrefix("\uFEFF")) }
  return out
 }
 @Deprecated("Deprecated")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode!=PICK||resultCode!=RESULT_OK)return
  val uri=data?.data?:return
  try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); values=readFile(uri); status.text="Backup file loaded: ${values.size} barcode rows; header skipped." }
  catch(e:Exception){status.text="File read error: ${e.message}"}
 }
 private fun readFile(uri:Uri):MutableList<String> {
  val csv=uri.lastPathSegment?.lowercase()?.endsWith(".csv")==true||contentResolver.getType(uri)?.contains("csv")==true
  if(csv) return BufferedReader(InputStreamReader(contentResolver.openInputStream(uri)!!)).useLines { it.drop(1).map { line->line.substringBefore(",").trim().trim('"') }.filter{it.isNotEmpty()}.toMutableList() }
  contentResolver.openInputStream(uri).use { stream ->
   val wb=WorkbookFactory.create(stream); val sh=wb.getSheetAt(0); val out=mutableListOf<String>()
   for(r in 1..sh.lastRowNum){val v=sh.getRow(r)?.getCell(0)?.toString()?.trim().orEmpty();if(v.isNotEmpty())out.add(v)}
   wb.close(); return out
  }
 }
}

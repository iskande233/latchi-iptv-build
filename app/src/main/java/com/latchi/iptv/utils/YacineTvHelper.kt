package com.latchi.iptv.utils
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
object YacineTvHelper {
    private const val API_URL = "http://ver3.yacinelive.com"
    private const val KEY = "c!xZj+N9&G@Ev@vw"
    data class YacineMatch(val id: Long, val startTime: Long, val endTime: Long, val champions: String, val commentary: String, val team1Name: String, val team1Logo: String, val team2Name: String, val team2Logo: String, val channelName: String)
    private fun decrypt(encoded: String, key: String): String { val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1); val sb = StringBuilder(decoded.length); for (i in decoded.indices) sb.append((decoded[i].code xor key[i % key.length].code).toChar()); return sb.toString() }
    fun fetchMatches(): List<YacineMatch> { val conn = (URL("$API_URL/api/events").openConnection() as HttpURLConnection).apply { requestMethod="GET"; connectTimeout=15000; readTimeout=15000 }; if (conn.responseCode!=200) throw Exception("HTTP ${conn.responseCode}"); val ts=conn.getHeaderField("t")?:(System.currentTimeMillis()/1000).toString(); val json=JSONObject(decrypt(conn.inputStream.bufferedReader().readText(),KEY+ts)); val arr=json.optJSONArray("data")?:return emptyList(); val list=mutableListOf<YacineMatch>(); for(i in 0 until arr.length()){val it=arr.getJSONObject(i);val t1=it.optJSONObject("team_1")?:continue;val t2=it.optJSONObject("team_2")?:continue;list.add(YacineMatch(it.optLong("id"),it.optLong("start_time"),it.optLong("end_time"),it.optString("champions",""),it.optString("commentary",""),t1.optString("name","?"),t1.optString("logo",""),t2.optString("name","?"),t2.optString("logo",""),it.optString("channel","")))}; return list }
    fun getMatchStatus(m: YacineMatch): String { val now=System.currentTimeMillis()/1000; return when{now<m.startTime->"قادمة"; now in m.startTime..m.endTime->"🔴 مباشر"; else->"انتهت"} }
    fun formatMatchTime(m: YacineMatch): String { val now=System.currentTimeMillis()/1000; return when{now<m.startTime->{val sdf=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault());sdf.format(java.util.Date(m.startTime*1000))}; now in m.startTime..m.endTime->{val e=((now-m.startTime)/60).toInt();if(e<=45)"${e}'" else if(e<=60)"HT" else "${e-15}'"}; else->"FT"} }
}

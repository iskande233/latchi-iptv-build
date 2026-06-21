package com.latchi.iptv.screens
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.ChannelRefreshHelper
import com.latchi.iptv.utils.MatchChannelMapper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvUtils
import com.latchi.iptv.utils.YacineTvHelper
import com.latchi.iptv.screens.TvLivePreviewActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
class MatchesActivity : AppCompatActivity() {
    override fun attachBaseContext(nb: Context) { super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(nb)) }
    private lateinit var recyclerView: RecyclerView; private lateinit var progressBar: ProgressBar; private lateinit var emptyText: TextView
    private lateinit var backButton: TextView; private lateinit var refreshButton: TextView; private lateinit var lastUpdateText: TextView
    private lateinit var adapter: SmartMatchesAdapter; private val items = mutableListOf<SmartMatchItem>(); private var allChannels: List<Channel> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper()); private var autoRefreshRunnable: Runnable? = null
    override fun onCreate(s: Bundle?) { super.onCreate(s); TvUtils.applyOrientation(this); com.latchi.iptv.utils.ThemeManager.apply(this); setContentView(R.layout.activity_matches)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)
        recyclerView=findViewById(R.id.matchesRecyclerView); progressBar=findViewById(R.id.progressBar); emptyText=findViewById(R.id.emptyText)
        backButton=findViewById(R.id.backButton); refreshButton=findViewById(R.id.refreshButton); lastUpdateText=findViewById(R.id.lastUpdateText)
        backButton.setOnClickListener{finish()}; refreshButton.setOnClickListener{load()}
        adapter = SmartMatchesAdapter(items) { mi ->
            if (mi.channel != null) {
                if (TvUtils.isTv(this)) {
                    TvLivePreviewActivity.startWithChannels(this, mi.channel, listOf(mi.channel), mi.channel.category)
                } else {
                    PlayerActivity.start(this, mi.channel)
                }
            } else if (mi.yacineStream != null) {
                val ch = Channel(
                    name = mi.channelName ?: "Yacine TV",
                    logoUrl = "",
                    streamUrl = mi.yacineStream.url,
                    category = "Yacine TV Matches",
                    contentType = "live"
                )
                if (TvUtils.isTv(this)) {
                    TvLivePreviewActivity.startWithChannels(this, ch, listOf(ch), ch.category)
                } else {
                    PlayerActivity.start(this, ch)
                }
            } else {
                Toast.makeText(this, "لم نجد القناة الناقلة في سيرفرك الحالي، سيتم فتح البحث.", Toast.LENGTH_LONG).show()
                val searchIntent = Intent(this, ChannelListActivity::class.java).apply {
                    putExtra("extra_type", "live")
                    putExtra("extra_title", "بحث: ${mi.channelName ?: "قناة"}")
                    putExtra("search_query", mi.channelName ?: "")
                }
                startActivity(searchIntent)
            }
        }
        recyclerView.layoutManager=LinearLayoutManager(this); com.latchi.iptv.utils.TvFocusHelper.setupRecycler(recyclerView); recyclerView.adapter=adapter; load()
        autoRefreshRunnable=object:Runnable{override fun run(){load();uiHandler.postDelayed(this,120000L)}}; uiHandler.postDelayed(autoRefreshRunnable!!,120000L) }
    private fun load() {
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE

        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            progressBar.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.matches_load_failed)
            return
        }

        ChannelRefreshHelper.ensureFreshChannels(this, active, onlyLive = true) { result ->
            allChannels = result.channels
            if (result.usedCacheFallback && result.message.isNotBlank()) {
                Toast.makeText(this, "⚠️ تم استخدام آخر كاش متاح للقنوات الرياضية", Toast.LENGTH_SHORT).show()
            }

            thread {
                try {
                    val matches = YacineTvHelper.fetchMatches()
                    val resolved = matches.map { mt ->
                        val localChannel = if (allChannels.isNotEmpty() && mt.channelName.isNotBlank()) {
                            MatchChannelMapper.findChannelInSportsGroups(mt.channelName, allChannels)
                        } else null
                        val yacineStream = if (localChannel == null && mt.channelName.isNotBlank()) {
                            runCatching { YacineTvHelper.resolveStreamForChannelName(mt.channelName) }.getOrNull()
                        } else null
                        SmartMatchItem(mt, localChannel, mt.channelName, yacineStream)
                    }.toMutableList()

                    runOnUiThread {
                        items.clear()
                        items.addAll(resolved)
                        adapter.notifyDataSetChanged()
                        progressBar.visibility = View.GONE
                        if (items.isEmpty()) {
                            emptyText.visibility = View.VISIBLE
                            emptyText.text = getString(R.string.no_matches)
                        }
                        lastUpdateText.text = "🔄 ${getString(R.string.last_update)}: ${com.latchi.iptv.utils.DigitNormalizer.normalizeDigits(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))}"
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                        emptyText.text = getString(R.string.matches_load_failed)
                    }
                }
            }
        }
    }
    override fun onDestroy(){super.onDestroy();autoRefreshRunnable?.let{uiHandler.removeCallbacks(it)}}
    data class SmartMatchItem(val yacineMatch: YacineTvHelper.YacineMatch, val channel: Channel?, val channelName: String?, val yacineStream: YacineTvHelper.YacineStream? = null)
    class SmartMatchesAdapter(private val items:List<SmartMatchItem>,private val onClick:(SmartMatchItem)->Unit):RecyclerView.Adapter<SmartMatchesAdapter.VH>(){
        class VH(v:View):RecyclerView.ViewHolder(v){val league:TextView=v.findViewById(R.id.matchLeague);val status:TextView=v.findViewById(R.id.matchStatus);val homeLogo:ImageView=v.findViewById(R.id.homeLogo);val homeTeam:TextView=v.findViewById(R.id.homeTeam);val awayLogo:ImageView=v.findViewById(R.id.awayLogo);val awayTeam:TextView=v.findViewById(R.id.awayTeam);val vsScore:TextView=v.findViewById(R.id.vsScore);val time:TextView=v.findViewById(R.id.matchTime);val channel:TextView=v.findViewById(R.id.matchChannel)}
        override fun onCreateViewHolder(p:ViewGroup,t:Int): VH { val v=LayoutInflater.from(p.context).inflate(R.layout.item_match,p,false); com.latchi.iptv.utils.TvFocusHelper.setupFocusableItem(v); return VH(v) }
        override fun getItemCount()=items.size
        override fun onBindViewHolder(h:VH,i:Int){val m=items[i];val mt=m.yacineMatch; h.league.text=mt.champions;val st=YacineTvHelper.getMatchStatus(mt);h.status.text=st
            h.status.setBackgroundColor(when{st.contains("مباشر")->android.graphics.Color.parseColor("#FF4444");st=="قادمة"->android.graphics.Color.parseColor("#4ADE80");else->android.graphics.Color.parseColor("#888888")})
            h.homeTeam.text=mt.team1Name;h.awayTeam.text=mt.team2Name
            Glide.with(h.itemView.context).load(mt.team1Logo.ifBlank{null}).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(h.homeLogo)
            Glide.with(h.itemView.context).load(mt.team2Logo.ifBlank{null}).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(h.awayLogo)
            h.vsScore.text="VS";h.time.text=YacineTvHelper.formatMatchTime(mt)
            if(!m.channelName.isNullOrBlank()){h.channel.visibility=View.VISIBLE;val ct=if(mt.commentary.isNotBlank())" | 🎙️ ${mt.commentary}" else "";h.channel.text=when{m.channel!=null->"📺 ${m.channelName} ✓$ct";m.yacineStream!=null->"📺 ${m.channelName} • Yacine ✓$ct";else->"📺 ${m.channelName}$ct"};h.channel.setTextColor(if(m.channel!=null||m.yacineStream!=null)android.graphics.Color.parseColor("#4ADE80") else android.graphics.Color.parseColor("#FFFFFF"))}else h.channel.visibility=View.GONE
            h.itemView.setOnClickListener{onClick(m)}}}

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}

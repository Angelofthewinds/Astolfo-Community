package xyz.astolfo.astolfocommunity.cluster

import com.github.salomonbrys.kotson.jsonObject
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import okhttp3.Request
import okhttp3.WebSocketListener
import xyz.astolfo.astolfocommunity.ASTOLFO_GSON
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.AstolfoWebSocketClient
import java.util.concurrent.TimeUnit

class ClusterWebSocketClient(private val application: AstolfoCommunityApplication) {

    private val webSocketClient: AstolfoWebSocketClient

    private sealed class ClusterWSEvent {
        class UpdateStats(val stats: List<ShardStats>) : ClusterWSEvent()
        class OutgoingEvent(val outgoingEvent: ClusterOutgoingEvent) : ClusterWSEvent()
    }

    private val clusterActor = actor<ClusterWSEvent>(capacity = Channel.UNLIMITED) {
        for (event in channel) {
            handleEvent(event)
        }
    }

    private suspend fun handleEvent(event: ClusterWSEvent) {
        when (event) {
            is ClusterWSEvent.UpdateStats -> {
                webSocketClient.send(jsonObject(
                        "t" to "UPDATE_STATS",
                        "d" to ASTOLFO_GSON.toJsonTree(event.stats)
                ).toString())
            }
        }
    }

    init {
        val request = Request.Builder()
                .url(application.properties.cluster_ip)
                .header("clusterId", "0")
                .header("shardRangeStart", "0")
                .header("shardRangeEnd", (application.properties.shard_count - 1).toString())
                .header("shardtotal", (application.properties.shard_count - 1).toString())

        webSocketClient = AstolfoWebSocketClient("Cluster", request, object : WebSocketListener() {

        })
        webSocketClient.startBlocking()
    }

    fun init(){
        launch {
            while(isActive){
                val shards = application.shardManager.shards
                clusterActor.send(ClusterWSEvent.UpdateStats(shards.map {
                    ShardStats(it.shardInfo.shardId, it.guilds.size, it.textChannels.size + it.voiceChannels.size, it.users.size, application.musicManager.sessionCount, it.status.name, System.currentTimeMillis())
                }))
                delay(30, TimeUnit.SECONDS)
            }
        }
    }

    class ShardStats(
            val shardID: Int,
            val guilds: Int,
            val channels: Int,
            val users: Int,
            val musicSessions: Int,
            val status: String,
            val lastUpdate: Long
    )

}
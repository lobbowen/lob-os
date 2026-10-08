package lobos

import lobos.capability.ChannelState

/**
 * 通道不通时界面该显示什么 —— **从状态派生**，不是一张写死的常量表。
 *
 * 以前这里叫 `ChannelStatusText.DOWN`，是「状态 → 文字」的常量表；
 * 那是把状态抄成第二份，两份会漂。改成按状态取值：加一个状态就多一行，
 * 不用改别处。
 *
 * `SetupActivity` 只在通道不通时显示这个条（通了就 `View.GONE`），
 * 但三种不通的原因要分开说 —— 用户该知道是「从没试过」还是「试过且断了」。
 */
object ChannelStatusText {

    fun of(status: lobos.capability.ChannelStatus): String = when (status.outcome) {
        ChannelState.LIVE -> "通道在线"
        ChannelState.NEVER_RUN -> "通道未建立 —— 点上方按钮开始配对"
        ChannelState.DEAD ->
            if (status.detail.isBlank()) "通道已断开 —— 点上方按钮重连"
            else "通道已断开（${status.detail}）—— 点上方按钮重连"
    }
}
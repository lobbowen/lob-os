const pageObject = {
  data: {
    result: '点上面按钮测能力',
    endpoint: '(尚未取到)'
  },

  onReady() {
    const info = app.getInfo()
    const be = (info && info.backend) || {}
    if (be.endpoint) {
      this.setData({ endpoint: be.endpoint })
    }
  },

  probeOpen() {
    this.bridge('openApp', { id: 'com.lobos.fixture' })
  },

  probeIcon() {
    this.bridge('desktopIcon.add', { id: 'com.lobos.fixture' })
  },

  probeIconState() {
    this.bridge('desktopIcon.state', { id: 'com.lobos.fixture' })
  },

  probeEndpoint() {
    this.bridge('backendEndpoint', { id: 'com.lobos.fixture' })
  },

  bridge(event, data) {
    const self = this
    self.setData({ result: '调用 ' + event + ' …' })
    let settled = false
    const timer = setTimeout(function () {
      if (settled) return
      settled = true
      self.setData({ result: event + ' → 8 秒无响应' })
    }, 8000)

    wx.extBridge({
      event: event,
      module: 'lobos',
      data: data,
      success: function (res) {
        if (settled) return
        settled = true
        clearTimeout(timer)
        self.setData({ result: event + ' 成功 → ' + JSON.stringify(res) })
      },
      fail: function (err) {
        if (settled) return
        settled = true
        clearTimeout(timer)
        self.setData({ result: event + ' 失败 → ' + JSON.stringify(err) })
      }
    })
  }
}

export default pageObject

const pageObject = {
  data: {
    title: 'LobOS 快应用',
    message: '这是端到端验证包',
    endpoint: '(尚未取到)',
    count: 0
  },

  onReady() {
    const info = app.getInfo()
    const be = (info && info.backend) || {}
    if (be.endpoint) {
      this.setData({ endpoint: be.endpoint })
    }
  },

  onTap() {
    this.setData({ count: this.data.count + 1 })
  }
}

export default pageObject

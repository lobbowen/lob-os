const pageObject = {
  data: {
    title: 'LobOS 快应用',
    message: '这是端到端验证包',
    endpoint: '(由能力事件返回)',
    count: 0
  },


  onTap() {
    this.setData({ count: this.data.count + 1 })
  }
}

Page(pageObject)

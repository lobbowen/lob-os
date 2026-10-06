const pageObject = {
  data: {
    who: '第二个快应用 com.lobos.second',
    count: 0
  },

  onTap() {
    this.setData({ count: this.data.count + 1 })
  }
}

Page(pageObject)

/* Texts in Indonesian, English and Chinese. %1 and %2 are filled in. ES5. */
(function (root) {
  'use strict';
  var K = ['home', 'live', 'guide', 'film', 'search', 'settings'];
  var S = {
    id: {
      home: 'Beranda', live: 'TV Langsung', guide: 'Jadwal', film: 'Film & Series', search: 'Cari', settings: 'Pengaturan',
      morning: 'Selamat pagi', noon: 'Selamat siang', afternoon: 'Selamat sore', night: 'Selamat malam',
      all: 'Semua', fav: 'Favorit', watch: 'Tonton', cont: 'Lanjutkan menonton', onAir: 'Sedang tayang', movies: 'Film', series: 'Series',
      cats: { national: 'Nasional', sports: 'Olahraga', movies: 'Film & Sinema', entertainment: 'Hiburan', news: 'Berita', kids: 'Anak', documentary: 'Dokumenter', music: 'Musik', lifestyle: 'Gaya Hidup', religious: 'Religi', tvri: 'TVRI Daerah', local: 'TV Daerah', other: 'Lainnya' },
      loading: 'Memuat saluran…', failed: 'Daftar saluran tidak bisa dimuat. Periksa internet lalu muat ulang di Pengaturan.', empty: 'Belum ada isinya.',
      emptyFav: 'Belum ada favorit. Sorot saluran lalu tekan tombol merah.', emptyFilm: 'Belum ada film atau series. Tambahkan playlist berisi tautan pixeldrain atau grup Film/Series.',
      count: '%1 saluran', episodes: '%1 episode', liveHint: 'OK tonton · Merah favorit · CH pindah halaman', now: 'Kini', next: 'Berikutnya', noGuide: 'Jadwal belum tersedia', guideLoading: 'Memuat jadwal…',
      remind: 'Ingatkan', reminded: 'Pengingat dipasang', unreminded: 'Pengingat dihapus', startsNow: 'Mulai sekarang', guideHint: 'OK tonton atau pasang pengingat',
      tune: 'Menyambungkan…', backup: 'Mencoba cadangan %1 dari %2…', retryIn: 'Mencoba lagi dalam %1 detik', waiting: 'Tekan OK untuk mencoba lagi.', slateKeys: 'OK coba lagi · Atas/Bawah ganti saluran · Kembali ke daftar',
      channels: 'Daftar saluran', options: 'Pilihan', playerHint: 'Kiri daftar saluran · Kanan pilihan · 0 saluran sebelumnya', source: 'Sumber siaran: %1 dari %2', favAdd: 'Tambah ke favorit', favDel: 'Hapus dari favorit',
      sleep: 'Pengatur tidur: %1', off: 'Mati', minutes: '%1 menit', sleepDone: 'Pengatur tidur selesai. Selamat beristirahat.', schedule: 'Jadwal saluran ini', exitPlayer: 'Kembali ke daftar',
      added: 'Ditambahkan ke favorit', removed: 'Dihapus dari favorit', resume: 'Lanjutkan dari %1', restart: 'Putar dari awal', watched: 'Selesai',
      keyboardSpace: 'Spasi', keyboardDelete: 'Hapus', keyboardDone: 'Selesai', typeSearch: 'Cari saluran, acara, atau film', results: 'Hasil', programmes: 'Acara',
      who: 'Siapa yang menonton?', owner: 'Saya', kidsProfile: 'Anak', guest: 'Tamu', kidsNote: 'Saluran anak & dokumenter', switchProfile: 'Ganti profil',
      pin: 'Masukkan PIN orang tua', pinNew: 'Buat PIN orang tua (4 angka)', pinWrong: 'PIN salah', pinSaved: 'PIN disimpan', timeUp: 'Waktu menonton hari ini sudah habis.', addTime: 'Tambah 30 menit (PIN)',
      setProfile: 'Nama', setLang: 'Bahasa', setCity: 'Kota (cuaca & salat)', setCountries: 'Negara saluran', setCats: 'Kategori kesukaan', setSort: 'Urutan saluran', setPlaylist: 'Playlist (M3U)', setReload: 'Muat ulang saluran dan jadwal',
      setPrayer: 'Waktu salat', setAsk: 'Tanya profil saat dibuka', setPin: 'PIN orang tua', setKids: 'Batas waktu Anak per hari', setHistory: 'Hapus riwayat tontonan', setUpdate: 'Cek pembaruan', setSetup: 'Ulangi pengaturan awal', setAbout: 'Tentang',
      sortPopularity: 'Populer', sortAsc: 'A–Z', sortDesc: 'Z–A', on: 'Aktif', builtIn: 'Bawaan', unset: 'Belum diatur', done: 'Selesai', skip: 'Lewati', continue_: 'Lanjut', back: 'Kembali', cancel: 'Batal', clear: 'Bersihkan', cleared: 'Riwayat dihapus', reloading: 'Memuat ulang…', badUrl: 'Alamat harus diawali http:// atau https://',
      obLang: 'Pilih bahasa', obName: 'Siapa namamu?', obCity: 'Di kota mana kamu tinggal?', obCountries: 'Saluran dari negara mana?', obCats: 'Apa yang suka kamu tonton?', obPrayer: 'Tampilkan waktu salat?', obReady: 'Siap menonton', yes: 'Ya', no: 'Tidak', notFound: 'Kota tidak ditemukan', searching: 'Mencari…',
      updateTitle: 'Tahoe TV %1 tersedia', updateBody: 'Versi terpasang %1. Di webOS pembaruan dipasang dari komputer: ambil kode terbaru lalu jalankan webos/install-tv.sh.', later: 'Nanti', upToDate: 'Sudah versi terbaru (%1)', checkFailed: 'Tidak bisa memeriksa pembaruan',
      about: 'Tahoe TV untuk webOS %1. TV langsung, jadwal, film & series. Sumber terbuka di github.com/fk0u/tahoe-tv', exitAsk: 'Keluar dari Tahoe TV?', exit: 'Keluar', prayer: { Fajr: 'Subuh', Dhuhr: 'Zuhur', Asr: 'Asar', Maghrib: 'Magrib', Isha: 'Isya' }
    },
    en: {
      home: 'Home', live: 'Live TV', guide: 'Guide', film: 'Movies & Series', search: 'Search', settings: 'Settings',
      morning: 'Good morning', noon: 'Good afternoon', afternoon: 'Good afternoon', night: 'Good evening',
      all: 'All', fav: 'Favorites', watch: 'Watch', cont: 'Continue watching', onAir: 'On now', movies: 'Movies', series: 'Series',
      cats: { national: 'National', sports: 'Sports', movies: 'Movies & Cinema', entertainment: 'Entertainment', news: 'News', kids: 'Kids', documentary: 'Documentary', music: 'Music', lifestyle: 'Lifestyle', religious: 'Religious', tvri: 'TVRI Regional', local: 'Local TV', other: 'Other' },
      loading: 'Loading channels…', failed: 'The channel list could not be loaded. Check the internet, then reload in Settings.', empty: 'Nothing here yet.',
      emptyFav: 'No favorites yet. Highlight a channel and press the red button.', emptyFilm: 'No movies or series yet. Add a playlist with pixeldrain links or a Film/Series group.',
      count: '%1 channels', episodes: '%1 episodes', liveHint: 'OK watch · Red favorite · CH page', now: 'Now', next: 'Next', noGuide: 'No guide for this channel', guideLoading: 'Loading the guide…',
      remind: 'Remind me', reminded: 'Reminder set', unreminded: 'Reminder removed', startsNow: 'Starting now', guideHint: 'OK watch or set a reminder',
      tune: 'Connecting…', backup: 'Trying backup %1 of %2…', retryIn: 'Trying again in %1 s', waiting: 'Press OK to try again.', slateKeys: 'OK try again · Up/Down change channel · Back to the list',
      channels: 'Channel list', options: 'Options', playerHint: 'Left channel list · Right options · 0 previous channel', source: 'Source: %1 of %2', favAdd: 'Add to favorites', favDel: 'Remove from favorites',
      sleep: 'Sleep timer: %1', off: 'Off', minutes: '%1 min', sleepDone: 'Sleep timer finished. Rest well.', schedule: 'This channel’s guide', exitPlayer: 'Back to the list',
      added: 'Added to favorites', removed: 'Removed from favorites', resume: 'Resume from %1', restart: 'Start over', watched: 'Watched',
      keyboardSpace: 'Space', keyboardDelete: 'Delete', keyboardDone: 'Done', typeSearch: 'Search channels, programmes or movies', results: 'Results', programmes: 'Programmes',
      who: 'Who is watching?', owner: 'Me', kidsProfile: 'Kids', guest: 'Guest', kidsNote: 'Kids & documentary channels', switchProfile: 'Switch profile',
      pin: 'Enter the parental PIN', pinNew: 'Create a parental PIN (4 digits)', pinWrong: 'Wrong PIN', pinSaved: 'PIN saved', timeUp: 'Today’s watching time is used up.', addTime: 'Add 30 minutes (PIN)',
      setProfile: 'Name', setLang: 'Language', setCity: 'City (weather & prayer)', setCountries: 'Channel countries', setCats: 'Favorite categories', setSort: 'Channel order', setPlaylist: 'Playlist (M3U)', setReload: 'Reload channels and guide',
      setPrayer: 'Prayer times', setAsk: 'Ask for a profile at start', setPin: 'Parental PIN', setKids: 'Kids daily time limit', setHistory: 'Clear watch history', setUpdate: 'Check for updates', setSetup: 'Run first-time setup again', setAbout: 'About',
      sortPopularity: 'Popular', sortAsc: 'A–Z', sortDesc: 'Z–A', on: 'On', builtIn: 'Built-in', unset: 'Not set', done: 'Done', skip: 'Skip', continue_: 'Continue', back: 'Back', cancel: 'Cancel', clear: 'Clear', cleared: 'History cleared', reloading: 'Reloading…', badUrl: 'The address must start with http:// or https://',
      obLang: 'Choose a language', obName: 'What is your name?', obCity: 'Which city do you live in?', obCountries: 'Channels from which countries?', obCats: 'What do you like to watch?', obPrayer: 'Show prayer times?', obReady: 'Ready to watch', yes: 'Yes', no: 'No', notFound: 'City not found', searching: 'Searching…',
      updateTitle: 'Tahoe TV %1 is available', updateBody: 'Installed version %1. On webOS updates are installed from a computer: get the latest code and run webos/install-tv.sh.', later: 'Later', upToDate: 'You have the latest version (%1)', checkFailed: 'Could not check for updates',
      about: 'Tahoe TV for webOS %1. Live TV, guide, movies & series. Open source at github.com/fk0u/tahoe-tv', exitAsk: 'Leave Tahoe TV?', exit: 'Exit', prayer: { Fajr: 'Fajr', Dhuhr: 'Dhuhr', Asr: 'Asr', Maghrib: 'Maghrib', Isha: 'Isha' }
    },
    zh: {
      home: '首页', live: '电视直播', guide: '节目单', film: '电影和剧集', search: '搜索', settings: '设置',
      morning: '早上好', noon: '中午好', afternoon: '下午好', night: '晚上好',
      all: '全部', fav: '收藏', watch: '观看', cont: '继续观看', onAir: '正在播出', movies: '电影', series: '剧集',
      cats: { national: '全国', sports: '体育', movies: '影视', entertainment: '娱乐', news: '新闻', kids: '少儿', documentary: '纪录片', music: '音乐', lifestyle: '生活', religious: '宗教', tvri: 'TVRI 地方台', local: '地方电视', other: '其他' },
      loading: '正在加载频道…', failed: '无法加载频道列表。请检查网络后在设置中重新加载。', empty: '这里还没有内容。',
      emptyFav: '还没有收藏。选中频道后按红色键。', emptyFilm: '还没有电影或剧集。请添加包含 pixeldrain 链接或 Film/Series 分组的播放列表。',
      count: '%1 个频道', episodes: '%1 集', liveHint: '确认观看 · 红色键收藏 · 频道键翻页', now: '现在', next: '接下来', noGuide: '暂无节目单', guideLoading: '正在加载节目单…',
      remind: '提醒我', reminded: '已设置提醒', unreminded: '已取消提醒', startsNow: '即将开始', guideHint: '确认键观看或设置提醒',
      tune: '正在连接…', backup: '尝试备用源 %1/%2…', retryIn: '%1 秒后重试', waiting: '按确认键重试。', slateKeys: '确认重试 · 上/下换台 · 返回列表',
      channels: '频道列表', options: '选项', playerHint: '左：频道列表 · 右：选项 · 0 上一个频道', source: '信号源 %1/%2', favAdd: '加入收藏', favDel: '取消收藏',
      sleep: '睡眠定时：%1', off: '关闭', minutes: '%1 分钟', sleepDone: '睡眠定时结束，晚安。', schedule: '本频道节目单', exitPlayer: '返回列表',
      added: '已加入收藏', removed: '已取消收藏', resume: '从 %1 继续', restart: '从头播放', watched: '已看完',
      keyboardSpace: '空格', keyboardDelete: '删除', keyboardDone: '完成', typeSearch: '搜索频道、节目或电影', results: '结果', programmes: '节目',
      who: '谁在观看？', owner: '我', kidsProfile: '儿童', guest: '访客', kidsNote: '少儿和纪录片频道', switchProfile: '切换用户',
      pin: '请输入家长密码', pinNew: '设置家长密码（4 位数字）', pinWrong: '密码错误', pinSaved: '密码已保存', timeUp: '今天的观看时间已用完。', addTime: '增加 30 分钟（需密码）',
      setProfile: '名字', setLang: '语言', setCity: '城市（天气与礼拜时间）', setCountries: '频道国家', setCats: '喜欢的类别', setSort: '频道排序', setPlaylist: '播放列表 (M3U)', setReload: '重新加载频道和节目单',
      setPrayer: '礼拜时间', setAsk: '启动时选择用户', setPin: '家长密码', setKids: '儿童每日时长', setHistory: '清除观看记录', setUpdate: '检查更新', setSetup: '重新进行初始设置', setAbout: '关于',
      sortPopularity: '热门', sortAsc: 'A–Z', sortDesc: 'Z–A', on: '开启', builtIn: '内置', unset: '未设置', done: '完成', skip: '跳过', continue_: '继续', back: '返回', cancel: '取消', clear: '清空', cleared: '已清除记录', reloading: '正在重新加载…', badUrl: '地址必须以 http:// 或 https:// 开头',
      obLang: '选择语言', obName: '你叫什么名字？', obCity: '你住在哪个城市？', obCountries: '想看哪些国家的频道？', obCats: '你喜欢看什么？', obPrayer: '显示礼拜时间吗？', obReady: '可以开始观看了', yes: '是', no: '否', notFound: '找不到该城市', searching: '正在搜索…',
      updateTitle: 'Tahoe TV %1 可用', updateBody: '当前版本 %1。webOS 版需在电脑上更新：获取最新代码后运行 webos/install-tv.sh。', later: '稍后', upToDate: '已是最新版本（%1）', checkFailed: '无法检查更新',
      about: 'Tahoe TV webOS 版 %1。直播、节目单、电影和剧集。开源：github.com/fk0u/tahoe-tv', exitAsk: '要退出 Tahoe TV 吗？', exit: '退出', prayer: { Fajr: '晨礼', Dhuhr: '晌礼', Asr: '晡礼', Maghrib: '昏礼', Isha: '宵礼' }
    }
  };
  var api = {
    LANGS: ['id', 'en', 'zh'], NAMES: { id: 'Bahasa Indonesia', en: 'English', zh: '中文' }, MENU: K,
    pick: function (code) { code = String(code || '').toLowerCase().slice(0, 2); return code === 'in' ? 'id' : (S[code] ? code : 'en'); },
    strings: function (lang) { return S[lang] || S.en; },
    fill: function (s, a, b) { return String(s).replace('%1', a).replace('%2', b); }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TahoeText = api;
})(this);

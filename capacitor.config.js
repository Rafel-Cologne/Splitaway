/** @type {import('@capacitor/cli').CapacitorConfig} */
const config = {
  appId:   'com.splitaway.app',
  appName: 'Splitaway',
  webDir:  'public',

  android: {
    allowMixedContent: false,
    backgroundColor: '#0a0a0f',
  },

  plugins: {
    PushNotifications: {
      presentationOptions: ['badge', 'sound', 'alert'],
    },
    LocalNotifications: {
      smallIcon:  'ic_stat_icon_config_sample',
      iconColor:  '#6c63ff',
    },
    // Нативный OCR плагин (ML Kit Document Scanner + Text Recognition)
    ReceiptScanner: {},
  },
};

module.exports = config;

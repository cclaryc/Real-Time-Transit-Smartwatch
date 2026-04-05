#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/display.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/services/nus.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/printk.h>

#include <lvgl.h>
#include <lvgl_zephyr.h>

#define DEVICE_NAME             "ZEPHYR"
#define DEVICE_NAME_LEN         (sizeof(DEVICE_NAME) - 1)

#define DISPLAY_NODE            DT_CHOSEN(zephyr_display)
#define BACKLIGHT_NODE          DT_NODELABEL(lcd_backlight)

#define RX_BUF_SIZE 256

static char rx_text[RX_BUF_SIZE] = "Waiting BLE message...";
static char assembled_text[RX_BUF_SIZE];
static size_t assembled_len;
static bool assembling;
static atomic_t rx_dirty;
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA(BT_DATA_NAME_COMPLETE, DEVICE_NAME, DEVICE_NAME_LEN),
};
static const struct bt_data sd[] = {
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, BT_UUID_NUS_SRV_VAL),
};
static const struct gpio_dt_spec lcd_backlight =
	GPIO_DT_SPEC_GET(BACKLIGHT_NODE, gpios);

/* ---------- UI state ---------- */

static lv_obj_t *title_label;
static lv_obj_t *status_label;
static lv_obj_t *rx_label;

static atomic_t rx_dirty;

/* ---------- Helpers ---------- */

static int init_display(const struct device **display_dev)
{
	const struct device *display = DEVICE_DT_GET(DISPLAY_NODE);
	int ret;

	if (!gpio_is_ready_dt(&lcd_backlight)) {
		printk("LCD backlight GPIO is not ready\n");
		return -ENODEV;
	}

	ret = gpio_pin_configure_dt(&lcd_backlight, GPIO_OUTPUT_ACTIVE);
	if (ret < 0) {
		printk("Backlight GPIO setup failed: %d\n", ret);
		return ret;
	}

	if (!device_is_ready(display)) {
		k_sleep(K_MSEC(250));
		ret = device_init(display);
		if (ret < 0) {
			printk("Display init failed: %d\n", ret);
			return ret;
		}
	}

	if (!device_is_ready(display)) {
		printk("Display device is not ready\n");
		return -ENODEV;
	}

	*display_dev = display;
	return 0;
}

static int init_ui(void)
{
	lv_obj_t *screen;

	screen = lv_screen_active();
	lv_obj_set_style_bg_color(screen, lv_color_hex(0x000000), 0);
	lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

	title_label = lv_label_create(screen);
	lv_label_set_text(title_label, "STB");
	lv_obj_set_style_text_color(title_label, lv_color_hex(0xFFFFFF), 0);
	lv_obj_set_style_text_font(title_label, &lv_font_montserrat_42, 0);
	lv_obj_align(title_label, LV_ALIGN_TOP_MID, 0, 14);

	// status_label = lv_label_create(screen);
	// lv_label_set_text(status_label, "Waiting for phone...");
	// lv_obj_set_style_text_color(status_label, lv_color_hex(0x8CFFB5), 0);
	// lv_obj_set_style_text_font(status_label, &lv_font_montserrat_14, 0);
	// lv_obj_align(status_label, LV_ALIGN_TOP_MID, 0, 58);

	rx_label = lv_label_create(screen);
	lv_label_set_long_mode(rx_label, LV_LABEL_LONG_WRAP);
	lv_obj_set_width(rx_label, 210);
	lv_label_set_text(rx_label, rx_text);
	lv_obj_set_style_text_color(rx_label, lv_color_hex(0xFFD166), 0);
	lv_obj_set_style_text_font(rx_label, &lv_font_montserrat_14, 0);	
	lv_obj_align(rx_label, LV_ALIGN_CENTER, 0, 15);

	return 0;
}

static void update_rx_ui(void)
{
	if (!atomic_cas(&rx_dirty, 1, 0)) {
		return;
	}

	lv_label_set_text(rx_label, rx_text);
	// lv_label_set_text(status_label, "BLE message received");
}

/* ---------- BLE callbacks ---------- */

static void notif_enabled(bool enabled, void *ctx)
{
	ARG_UNUSED(ctx);

	printk("%s() - %s\n", __func__, enabled ? "Enabled" : "Disabled");

	atomic_set(&rx_dirty, 1);

	if (enabled) {
		// snprintk(rx_text, sizeof(rx_text), "Phone connected.\nNotifications ON.");
	} else {
		snprintk(rx_text, sizeof(rx_text), "Phone connected.\nNotifications OFF.");
	}
}

static void received(struct bt_conn *conn, const void *data, uint16_t len, void *ctx)
{
    const char *bytes = (const char *)data;

    ARG_UNUSED(conn);
    ARG_UNUSED(ctx);

    printk("%s() - Len: %d, Message: %.*s\n", __func__, len, len, bytes);

    for (uint16_t i = 0; i < len; i++) {
        char c = bytes[i];

        if (c == '*') {
            assembling = true;
            assembled_len = 0;
            memset(assembled_text, 0, sizeof(assembled_text));
            continue;
        }

        if (!assembling) {
            continue;
        }

        if (c == '#') {
            assembled_text[assembled_len] = '\0';

            size_t out = 0;
            for (size_t j = 0; j < assembled_len && out < sizeof(rx_text) - 1; j++) {
                rx_text[out++] = (assembled_text[j] == '|') ? '\n' : assembled_text[j];
            }
            rx_text[out] = '\0';

            assembling = false;
            atomic_set(&rx_dirty, 1);
            continue;
        }

        if (assembled_len < sizeof(assembled_text) - 1) {
            assembled_text[assembled_len++] = c;
        }
    }
}

static struct bt_nus_cb nus_listener = {
	.notif_enabled = notif_enabled,
	.received = received,
};

/* ---------- Main ---------- */

int main(void)
{
	const struct device *display;
	uint32_t sleep_ms;
	int err;

	printk("Hacktor BLE display demo\n");

	err = init_display(&display);
	if (err < 0) {
		return 0;
	}

	err = lvgl_init();
	if (err < 0) {
		printk("LVGL init failed: %d\n", err);
		return 0;
	}

	lvgl_lock();
	err = init_ui();
	if (err == 0) {
		lv_timer_handler();
	}
	lvgl_unlock();

	if (err < 0) {
		return 0;
	}

	err = display_blanking_off(display);
	if (err < 0 && err != -ENOSYS) {
		printk("Failed to enable display output: %d\n", err);
		return 0;
	}

	err = bt_nus_cb_register(&nus_listener, NULL);
	if (err) {
		printk("Failed to register NUS callback: %d\n", err);
		return 0;
	}

	err = bt_enable(NULL);
	if (err) {
		printk("Failed to enable bluetooth: %d\n", err);
		return 0;
	}

	err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_1, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	if (err) {
		printk("Failed to start advertising: %d\n", err);
		return 0;
	}

	// printk("BLE advertising started\n");

	lvgl_lock();
	// lv_label_set_text(status_label, "Advertising BLE...");
	lv_timer_handler();
	lvgl_unlock();

	while (1) {
		static int tick;
		// const char *hello_world = "Hello World!\n";

		lvgl_lock();
		update_rx_ui();
		sleep_ms = lv_timer_handler();
		lvgl_unlock();

		// if (++tick >= 30) {
		// 	err = bt_nus_send(NULL, hello_world, strlen(hello_world));
		// 	printk("Data send - Result: %d\n", err);
		// 	tick = 0;
		// }

		if (sleep_ms == LV_NO_TIMER_READY || sleep_ms > 100U) {
			sleep_ms = 100U;
		}
		if (sleep_ms < 10U) {
			sleep_ms = 10U;
		}

		k_sleep(K_MSEC(sleep_ms));
	}

	return 0;
}
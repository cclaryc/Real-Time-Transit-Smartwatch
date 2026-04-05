#include "panel.h"

#include <zephyr/device.h>
#include <zephyr/drivers/display.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/input/input.h>
#include <zephyr/kernel.h>
#include <zephyr/shell/shell.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/printk.h>
#include <lvgl.h>
#include <lvgl_zephyr.h>

#define DISPLAY_NODE DT_CHOSEN(zephyr_display)
#define BACKLIGHT_NODE DT_NODELABEL(lcd_backlight)
#define TOUCH_NODE DT_CHOSEN(zephyr_touch)
#define DISPLAY_WIDTH DT_PROP(DISPLAY_NODE, width)
#define DISPLAY_HEIGHT DT_PROP(DISPLAY_NODE, height)
#define TOUCH_DOT_SIZE 18
#define TOUCH_DOT_COLOR_HEX 0x365AFF

static const struct gpio_dt_spec lcd_backlight = GPIO_DT_SPEC_GET(BACKLIGHT_NODE, gpios);
static const struct device *const touch_dev = DEVICE_DT_GET(TOUCH_NODE);

static atomic_t touch_x;
static atomic_t touch_y;
static atomic_t touch_pressed;
static atomic_t touch_dirty;

static struct display_capabilities display_cap = {
	.x_resolution = DISPLAY_WIDTH,
	.y_resolution = DISPLAY_HEIGHT,
	.current_orientation = DISPLAY_ORIENTATION_NORMAL,
};

static lv_obj_t *status_label;
static lv_obj_t *touch_dot;

static int clamp_coord(int value, int limit)
{
	if (value < 0) {
		return 0;
	}

	if (value > limit) {
		return limit;
	}

	return value;
}

static void transform_touch_point(int raw_x, int raw_y, int *screen_x, int *screen_y)
{
	switch (display_cap.current_orientation) {
	case DISPLAY_ORIENTATION_NORMAL:
		*screen_x = raw_x;
		*screen_y = raw_y;
		break;
	case DISPLAY_ORIENTATION_ROTATED_90:
		*screen_x = raw_y;
		*screen_y = display_cap.y_resolution - raw_x;
		break;
	case DISPLAY_ORIENTATION_ROTATED_180:
		*screen_x = display_cap.x_resolution - raw_x;
		*screen_y = display_cap.y_resolution - raw_y;
		break;
	case DISPLAY_ORIENTATION_ROTATED_270:
		*screen_x = display_cap.x_resolution - raw_y;
		*screen_y = raw_x;
		break;
	default:
		*screen_x = raw_x;
		*screen_y = raw_y;
		break;
	}

	*screen_x = clamp_coord(*screen_x, display_cap.x_resolution - 1);
	*screen_y = clamp_coord(*screen_y, display_cap.y_resolution - 1);
}

static void touch_event_callback(struct input_event *evt, void *user_data)
{
	ARG_UNUSED(user_data);

	switch (evt->code) {
	case INPUT_ABS_X:
		atomic_set(&touch_x, evt->value);
		break;
	case INPUT_ABS_Y:
		atomic_set(&touch_y, evt->value);
		break;
	case INPUT_BTN_TOUCH:
		atomic_set(&touch_pressed, evt->value);
		break;
	default:
		break;
	}

	if (evt->sync) {
		atomic_set(&touch_dirty, 1);
	}
}
INPUT_CALLBACK_DEFINE(touch_dev, touch_event_callback, NULL);

static void update_touch_ui(void)
{
	char text[32];
	int x;
	int y;
	int screen_x;
	int screen_y;
	bool pressed;

	if (!atomic_cas(&touch_dirty, 1, 0)) {
		return;
	}

	x = atomic_get(&touch_x);
	y = atomic_get(&touch_y);
	pressed = atomic_get(&touch_pressed) != 0;
	transform_touch_point(x, y, &screen_x, &screen_y);

	if (pressed) {
		lv_obj_clear_flag(touch_dot, LV_OBJ_FLAG_HIDDEN);
		lv_obj_set_pos(touch_dot,
			       clamp_coord(screen_x - TOUCH_DOT_SIZE / 2,
					   display_cap.x_resolution - TOUCH_DOT_SIZE),
			       clamp_coord(screen_y - TOUCH_DOT_SIZE / 2,
					   display_cap.y_resolution - TOUCH_DOT_SIZE));
		snprintk(text, sizeof(text), "Touch %d,%d", screen_x, screen_y);
		lv_label_set_text(status_label, text);
	} else {
		lv_obj_add_flag(touch_dot, LV_OBJ_FLAG_HIDDEN);
		lv_label_set_text(status_label, "Touch released");
	}
}

static int cmd_app_status(const struct shell *sh, size_t argc, char **argv)
{
	int raw_x;
	int raw_y;
	int screen_x;
	int screen_y;
	bool pressed;

	ARG_UNUSED(argc);
	ARG_UNUSED(argv);

	raw_x = atomic_get(&touch_x);
	raw_y = atomic_get(&touch_y);
	pressed = atomic_get(&touch_pressed) != 0;
	transform_touch_point(raw_x, raw_y, &screen_x, &screen_y);

	shell_print(sh, "display: %ux%u orientation=%u",
		    display_cap.x_resolution,
		    display_cap.y_resolution,
		    display_cap.current_orientation);
	shell_print(sh, "touch: %s raw=%d,%d screen=%d,%d",
		    pressed ? "pressed" : "released",
		    raw_x, raw_y, screen_x, screen_y);

	return 0;
}
SHELL_CMD_REGISTER(app, NULL, "Show current app and touch status", cmd_app_status);

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

	display_get_capabilities(display, &display_cap);

	*display_dev = display;
	return 0;
}

static int init_ui(void)
{
	lv_obj_t *screen;
	lv_obj_t *hello_label;

	screen = lv_screen_active();
	lv_obj_set_style_bg_color(screen, lv_color_hex(0x000000), 0);
	lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

	hello_label = lv_label_create(screen);
	lv_label_set_text(hello_label, "Hello CC!");
	lv_obj_set_style_text_color(hello_label, lv_color_hex(0xFFFFFF), 0);
	lv_obj_set_style_text_font(hello_label, &lv_font_montserrat_42, 0);
	lv_obj_align(hello_label, LV_ALIGN_CENTER, 0, 0);

	status_label = lv_label_create(screen);
	lv_label_set_text(status_label, "Touch the screen");
	lv_obj_set_style_text_color(status_label, lv_color_hex(0x8CFFB5), 0);
	lv_obj_set_style_text_font(status_label, &lv_font_montserrat_14, 0);
	lv_obj_align(status_label, LV_ALIGN_BOTTOM_MID, 0, -14);

	touch_dot = lv_obj_create(screen);
	lv_obj_remove_style_all(touch_dot);
	lv_obj_set_size(touch_dot, TOUCH_DOT_SIZE, TOUCH_DOT_SIZE);
	lv_obj_set_style_radius(touch_dot, LV_RADIUS_CIRCLE, 0);
	/* This panel path swaps red/blue, so use a pre-swapped value to render orange. */
	lv_obj_set_style_bg_color(touch_dot, lv_color_hex(TOUCH_DOT_COLOR_HEX), 0);
	lv_obj_set_style_bg_opa(touch_dot, LV_OPA_COVER, 0);
	lv_obj_add_flag(touch_dot, LV_OBJ_FLAG_HIDDEN);

	return 0;
}

int panel_run(void)
{
	const struct device *display;
	uint32_t sleep_ms;
	int ret;

	if (!device_is_ready(touch_dev)) {
		printk("Touch device is not ready\n");
		return 0;
	}

	ret = init_display(&display);
	if (ret < 0) {
		return 0;
	}

	ret = lvgl_init();
	if (ret < 0) {
		printk("LVGL init failed: %d\n", ret);
		return 0;
	}

	lvgl_lock();
	ret = init_ui();
	if (ret == 0) {
		lv_timer_handler();
	}
	lvgl_unlock();

	if (ret < 0) {
		return 0;
	}

	ret = display_blanking_off(display);
	if (ret < 0 && ret != -ENOSYS) {
		printk("Failed to enable display output: %d\n", ret);
		return 0;
	}

	while (1) {
		lvgl_lock();
		update_touch_ui();
		sleep_ms = lv_timer_handler();
		lvgl_unlock();

		if (sleep_ms == LV_NO_TIMER_READY || sleep_ms > 100U) {
			sleep_ms = 100U;
		}
		if (sleep_ms < 10U) {
			sleep_ms = 10U;
		}

		k_sleep(K_MSEC(sleep_ms));
	}
}

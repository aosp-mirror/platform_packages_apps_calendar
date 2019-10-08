LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Include res dir from chips
chips_dir := ../../../frameworks/opt/chips/res
color_picker_dir := ../../../frameworks/opt/colorpicker/res
timezonepicker_dir := ../../../frameworks/opt/timezonepicker/res
res_dirs := $(chips_dir) $(color_picker_dir) $(timezonepicker_dir) res
src_dirs := src

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := com.android.calendar.*

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under,$(src_dirs))

# bundled
#LOCAL_STATIC_JAVA_LIBRARIES += \
#        android-common \
#        libchips \
#        calendar-common

# unbundled
LOCAL_STATIC_JAVA_LIBRARIES := \
        android-common \
        libchips \
        colorpicker \
        android-opt-timezonepicker \
        androidx.legacy_legacy-support-v4 \
        calendar-common

LOCAL_SDK_VERSION := current

LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, $(res_dirs))

LOCAL_PACKAGE_NAME := Calendar

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

LOCAL_PRODUCT_MODULE := true

LOCAL_AAPT_FLAGS := --auto-add-overlay
LOCAL_AAPT_FLAGS += --extra-packages com.android.ex.chips
LOCAL_AAPT_FLAGS += --extra-packages com.android.colorpicker
LOCAL_AAPT_FLAGS += --extra-packages com.android.timezonepicker

LOCAL_AAPT_FLAGS += --legacy

include $(BUILD_PACKAGE)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))

<template>
    <svg
        class="icon"
        aria-hidden="true"
        :width="props.size + 'em'"
        :height="props.size + 'em'"
        :style="iconStyle"
    >
        <use :xlink:href="'#' + props.name" fill="currentColor"></use>
    </svg>
</template>

<script setup >

// const props = withDefaults( {
//     color: '',
//     size: 1,
// })
const props = defineProps({
    color: {
    type: String,
    default: "",
  },
  size: {
    type: Number,
    default: 1,
  },
  name:{
    type: String,
    default: "",
  }
});

const iconStyle = computed(() => {
  return {
    color: props.color || "currentColor",
  };
});

</script>

<style scoped lang="scss">
.icon {
    display: inline-block;
    vertical-align: -0.15em;
    color: inherit;
    fill: currentColor;
    overflow: hidden;

}
</style>

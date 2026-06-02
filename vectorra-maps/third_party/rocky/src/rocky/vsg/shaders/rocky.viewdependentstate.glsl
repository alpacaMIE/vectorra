#pragma import_defined(ROCKY_BINDING_VIEW_DEPENDENT_STATE)

#ifndef ROCKY_BINDING_VIEW_DEPENDENT_STATE
#define ROCKY_BINDING_VIEW_DEPENDENT_STATE 10
#endif

layout(set = 1, binding = ROCKY_BINDING_VIEW_DEPENDENT_STATE) uniform RockyVDS {
    mat4 inverseViewMatrix;
    vec2 ellipsoidAxes;
    uint stereographic;
    float _padding[1];
} u_vds;

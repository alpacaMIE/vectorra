/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#pragma once
#include <rocky/vsg/Common.h>

namespace ROCKY_NAMESPACE
{
    // Shader binding set and binding points for VSG's view-dependent data.
    // See vsg::ViewDependentState
    constexpr int VSG_VIEW_DEPENDENT_DESCRIPTOR_SET_INDEX = 1;
    constexpr int VSG_VIEW_DEPENDENT_LIGHTS_BINDING = 0;
    constexpr int VSG_VIEW_DEPENDENT_VIEWPORTS_BINDING = 1;
    constexpr int VSG_VIEW_DEPENDENT_ROCKY_BINDING = 10;

    class MapNode;

    /**
    * Extends vsg::ViewDependentState to add data for Rocky rendering.
    * Shader usage (where "binding" === VSG_VIEW_DEPENDENT_ROCKY_BINDING)
    *
    *   layout(set = 1, binding = XXX) uniform RockyVDS {
    *       mat4 inverseViewMatrix;
    *       vec2 ellipsoidAxes;
    *       uint stereographic;
    *       float _padding[1];
    *   } u_vds;
    *
    */
    class ROCKY_EXPORT ViewDependentStateEx : public vsg::Inherit<vsg::ViewDependentState, ViewDependentStateEx>
    {
    public:
        ViewDependentStateEx(vsg::ref_ptr<vsg::View> vsgView) : Inherit(vsgView) {
            //nop
        }

        struct MyDescriptors
        {
            struct Uniforms
            {
                vsg::mat4 inverseViewMatrix;
                vsg::vec2 ellipsoidAxes;
                std::uint32_t stereographic; // bool
                float _padding[1];
            };
            vsg::ref_ptr<vsg::Data> data;
            vsg::ref_ptr<vsg::Descriptor> ubo;
        };

        MyDescriptors::Uniforms& uniforms() {
            return *static_cast<MyDescriptors::Uniforms*>(_myDescriptors.data->dataPointer());
        }

        void dirty() {
            _myDescriptors.data->dirty();
        }

    public:
        void init(vsg::ResourceRequirements& req) override;

        void traverse(vsg::RecordTraversal& rt) const override;

    protected:
        MyDescriptors _myDescriptors;
        mutable vsg::observer_ptr<MapNode> _mapNode;
    };
    

    //! Convenience function that adds the VDS descriptor bindings to a shader set.
    extern ROCKY_EXPORT void addViewDependentStateToShaderSet(
        vsg::ShaderSet* shaderSet,
        VkShaderStageFlags stageFlags = VK_SHADER_STAGE_ALL);


    //! Convenience function that enables VDS uniforms on a pipeline.
    extern ROCKY_EXPORT void enableViewDependentStateUniforms(
        vsg::GraphicsPipelineConfigurator* gpc);


    //! Retrieve the VDS from a view.
    inline ViewDependentStateEx* viewDependentState(vsg::View* view)
    {
        if (view)
            return dynamic_cast<ViewDependentStateEx*>(view->viewDependentState.get());
        else
            return nullptr;
    }
}

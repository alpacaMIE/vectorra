/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */

#include "ViewDependentState.h"
#include "MapNode.h"

using namespace ROCKY_NAMESPACE;


void
ViewDependentStateEx::init(vsg::ResourceRequirements& req)
{
    Inherit::init(req);

    // add our own descriptors to descriptorSet
    _myDescriptors.data = vsg::ubyteArray::create(sizeof(MyDescriptors::Uniforms));
    _myDescriptors.data->properties.dataVariance = vsg::DYNAMIC_DATA;
    _myDescriptors.ubo = vsg::DescriptorBuffer::create(_myDescriptors.data, VSG_VIEW_DEPENDENT_ROCKY_BINDING);

    // initialize to the defaults
    auto& uniforms = *static_cast<MyDescriptors::Uniforms*>(_myDescriptors.data->dataPointer());
    uniforms = MyDescriptors::Uniforms();

    // add it! It will automatically compile along with the others.
    this->descriptorSet->descriptors.emplace_back(_myDescriptors.ubo);

    // add its shader-stage binding to the layout.
    this->descriptorSetLayout->addBinding(VSG_VIEW_DEPENDENT_ROCKY_BINDING,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_ALL);
}


void
ViewDependentStateEx::traverse(vsg::RecordTraversal& rt) const
{
    // todo: update custom descriptors
    auto& uniforms = *static_cast<MyDescriptors::Uniforms*>(_myDescriptors.data->dataPointer());
    uniforms.inverseViewMatrix = vsg::inverse(view->camera->viewMatrix->transform());

    // ellipsoid params (TODO: don't need to update these constantly!)
    if (!_mapNode)
        _mapNode = detail::find<MapNode>(view);

    if (auto mapNode = _mapNode.ref_ptr())
    {
        uniforms.ellipsoidAxes.x = mapNode->srs().ellipsoid().semiMajorAxis();
        uniforms.ellipsoidAxes.y = mapNode->srs().ellipsoid().semiMinorAxis();
    }

    _myDescriptors.data->dirty();

    Inherit::traverse(rt);
}



void
ROCKY_NAMESPACE::addViewDependentStateToShaderSet(vsg::ShaderSet* shaderSet, VkShaderStageFlags stageFlags)
{
    // VSG view-dependent data. You must include it all even if you only intend to use
    // one of the uniforms.
    shaderSet->customDescriptorSetBindings.push_back(
        vsg::ViewDependentStateBinding::create(VSG_VIEW_DEPENDENT_DESCRIPTOR_SET_INDEX));

    shaderSet->addDescriptorBinding(
        "vsg_lights", "",
        VSG_VIEW_DEPENDENT_DESCRIPTOR_SET_INDEX,
        VSG_VIEW_DEPENDENT_LIGHTS_BINDING,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
        stageFlags, {});

    // VSG viewport state
    shaderSet->addDescriptorBinding(
        "vsg_viewports", "",
        VSG_VIEW_DEPENDENT_DESCRIPTOR_SET_INDEX,
        VSG_VIEW_DEPENDENT_VIEWPORTS_BINDING,
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
        stageFlags, {});

    shaderSet->addDescriptorBinding(
        "rocky_vds", "",
        VSG_VIEW_DEPENDENT_DESCRIPTOR_SET_INDEX,
        VSG_VIEW_DEPENDENT_ROCKY_BINDING,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
        stageFlags, {});
}

void
ROCKY_NAMESPACE::enableViewDependentStateUniforms(vsg::GraphicsPipelineConfigurator* gpc)
{
    gpc->enableDescriptor("vsg_lights");
    gpc->enableDescriptor("vsg_viewports");
    gpc->enableDescriptor("rocky_vds");
}
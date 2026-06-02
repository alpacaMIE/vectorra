/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "FutureNode.h"
#include <rocky/weejobs.h>

using namespace ROCKY_NAMESPACE;

FutureNode::FutureNode(Future<NodeResult> f, VSGContext v) :
    _future(f),
    _vsgcontext(v)
{
    //nop
}

void
FutureNode::traverse(vsg::RecordTraversal& record) const
{
    if (_future.available() && _future->ok())
    {
        _child = _future->value();

        // compile our new node
        if (_child && _vsgcontext)
            _vsgcontext->compile(_child);

        _future = {};
    }

    if (_child)
    {
        _child->accept(record);
    }
}

void
FutureNode::traverse(vsg::Visitor& visitor) 
{
    if (_child)
    {
        _child->accept(visitor);
    }
}

void
FutureNode::traverse(vsg::ConstVisitor& visitor) const 
{
    if (_child)
    {
        _child->accept(visitor);
    }
}

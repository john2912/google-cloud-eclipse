/*******************************************************************************
 * Copyright (c) 2005, 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     James Blackburn (Broadcom Corp.) - ongoing development
 *     Lars Vogel <Lars.Vogel@vogella.com> - Bug 473427
 *******************************************************************************/
package org.eclipse.core.internal.resources.mapping;

import java.util.*;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.mapping.*;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * A simple model provider that represents the resource model itself.
 *
 * @since 3.2
 */
public final class ResourceModelProvider extends ModelProvider {

	@Override
	public ResourceMapping[] getMappings(IResource resource, ResourceMappingContext context, IProgressMonitor monitor) {
		return new ResourceMapping[] {new SimpleResourceMapping(resource)};
	}

	@Override
	public ResourceMapping[] getMappings(ResourceTraversal[] traversals, ResourceMappingContext context, IProgressMonitor monitor) {
		Set<IAdaptable> result = new HashSet<>();
		for (int i = 0; i < traversals.length; i++) {
			ResourceTraversal traversal = traversals[i];
			IResource[] resources = traversal.getResources();
			int depth = traversal.getDepth();
			for (int j = 0; j < resources.length; j++) {
				IResource resource = resources[j];
				switch (depth) {
					case IResource.DEPTH_INFINITE :
						result.add(resource);
						break;
					case IResource.DEPTH_ONE :
						if (resource.getType() == IResource.FILE) {
							result.add(resource);
						} else {
							result.add(new ShallowContainer((IContainer) resource));
						}
						break;
					case IResource.DEPTH_ZERO :
						if (resource.getType() == IResource.FILE)
							result.add(resource);
						break;
				}
			}
		}
		ResourceMapping[] mappings = new ResourceMapping[result.size()];
		int i = 0;
		for (Iterator<?> iter = result.iterator(); iter.hasNext();) {
			Object element = iter.next();
			if (element instanceof IResource) {
				mappings[i++] = new SimpleResourceMapping((IResource) element);
			} else {
				mappings[i++] = new ShallowResourceMapping((ShallowContainer) element);
			}
		}
		return mappings;
	}
}
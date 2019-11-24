/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.physical;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.sql.impl.expression.Expression;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class SortPhysicalNode extends UniInputPhysicalNode {
    /** Expressions. */
    private List<Expression> expressions;

    /** Sort orders. */
    private List<Boolean> ascs;

    public SortPhysicalNode() {
        // No-op.
    }

    public SortPhysicalNode(PhysicalNode upstream, List<Expression> expressions, List<Boolean> ascs) {
        super(upstream);

        this.expressions = expressions;
        this.ascs = ascs;
    }

    public List<Expression> getExpressions() {
        return expressions;
    }

    public List<Boolean> getAscs() {
        return ascs;
    }

    @Override
    public void visit0(PhysicalNodeVisitor visitor) {
        visitor.onSortNode(this);
    }

    @Override
    public void writeData0(ObjectDataOutput out) throws IOException {
        out.writeObject(expressions);
        out.writeObject(ascs);
    }

    @Override
    public void readData0(ObjectDataInput in) throws IOException {
        expressions = in.readObject();
        ascs = in.readObject();
    }

    @Override
    public int hashCode() {
        return Objects.hash(upstream, expressions, ascs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SortPhysicalNode that = (SortPhysicalNode) o;

        return upstream.equals(that.upstream) && expressions.equals(that.expressions) && ascs.equals(that.ascs);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{expressions=" + expressions + ", ascs=" + ascs
            + ", upstream=" + upstream + '}';
    }
}

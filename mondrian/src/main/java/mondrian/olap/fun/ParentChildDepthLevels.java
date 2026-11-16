/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Sergei Semenkov
//
// All Rights Reserved.
*/
package mondrian.olap.fun;

import mondrian.calc.Calc;
import mondrian.calc.ExpCompiler;
import mondrian.calc.ListCalc;
import mondrian.calc.MemberCalc;
import mondrian.calc.TupleList;
import mondrian.calc.impl.AbstractListCalc;
import mondrian.calc.impl.UnaryTupleList;
import mondrian.mdx.HierarchyExpr;
import mondrian.mdx.ResolvedFunCall;
import mondrian.mdx.UnresolvedFunCall;
import mondrian.olap.Category;
import mondrian.olap.Cube;
import mondrian.olap.Dimension;
import mondrian.olap.Evaluator;
import mondrian.olap.Exp;
import mondrian.olap.Hierarchy;
import mondrian.olap.Id;
import mondrian.olap.Level;
import mondrian.olap.Member;
import mondrian.olap.OlapElement;
import mondrian.olap.SchemaReader;
import mondrian.olap.Syntax;
import mondrian.olap.Util;
import mondrian.olap.Validator;
import mondrian.olap.type.MemberType;
import mondrian.olap.type.SetType;
import mondrian.olap.type.Type;
import mondrian.rolap.RolapLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-depth levels of a parent-child hierarchy, {@code [Employees].[Level 01]} …
 * {@code [Employees].[Level NN]}, in MDX.
 *
 * <p>{@code MDSCHEMA_LEVELS} reports a parent-child hierarchy as one level per depth of its
 * tree, named the way Analysis Services names them, while the hierarchy itself keeps its one
 * physical level ({@code Hierarchy.getLevels()} is indexed by depth throughout the engine, so the
 * per-depth levels cannot be real {@link Level} objects). Power Query (Power BI import, Excel's
 * "From Analysis Services") builds its MDX from that rowset:
 * {@code DESCENDANTS([Employees].[Level 01].ALLMEMBERS, [Employees].[Level 07], LEAVES)}, which
 * failed with "MDX object '[Employees].[Level 01]' not found".
 *
 * <p>Those names are accepted where a per-depth level means something without a level object:
 * {@code <level>.MEMBERS}, {@code <level>.ALLMEMBERS} (the members at that depth) and
 * {@code DESCENDANTS(<member or set>, <level>[, <flag>])} (by tree depth, each flag as for a
 * level). {@link UnresolvedFunCall#accept} calls {@link #rewrite} before validating the
 * arguments; an element that really has the name (a level or a member called "Level 01") wins.
 * Level 01 is the hierarchy's root members, at depth 1 under the All member, 0 without one.
 */
public final class ParentChildDepthLevels {

    private static final Pattern NAME =
        Pattern.compile("Level\\s+0*([1-9][0-9]{0,3})", Pattern.CASE_INSENSITIVE);

    private ParentChildDepthLevels() {
    }

    /** One per-depth level: the hierarchy, its physical parent-child level and the tree depth. */
    static final class DepthLevel {
        final Hierarchy hierarchy;
        final Level level;
        final int treeDepth;
        final String uniqueName;

        DepthLevel(Hierarchy hierarchy, Level level, int treeDepth, String uniqueName) {
            this.hierarchy = hierarchy;
            this.level = level;
            this.treeDepth = treeDepth;
            this.uniqueName = uniqueName;
        }
    }

    /** The flags of {@code DESCENDANTS}, by tree depth. */
    enum Flag {
        SELF(true, false, false, false),
        AFTER(false, true, false, false),
        BEFORE(false, false, true, false),
        BEFORE_AND_AFTER(false, true, true, false),
        SELF_AND_AFTER(true, true, false, false),
        SELF_AND_BEFORE(true, false, true, false),
        SELF_BEFORE_AFTER(true, true, true, false),
        LEAVES(false, false, false, true);

        final boolean self;
        final boolean after;
        final boolean before;
        final boolean leaves;

        Flag(boolean self, boolean after, boolean before, boolean leaves) {
            this.self = self;
            this.after = after;
            this.before = before;
            this.leaves = leaves;
        }
    }

    /**
     * Returns the call with a per-depth level replaced, validated, or null when the call names
     * none (it is then validated as usual).
     */
    public static Exp rewrite(UnresolvedFunCall call, Validator validator) {
        final String name = call.getFunName();
        final Exp[] args = call.getArgs();
        if (call.getSyntax() == Syntax.Property && args.length == 1
            && (name.equalsIgnoreCase("Members") || name.equalsIgnoreCase("AllMembers")))
        {
            final DepthLevel depthLevel = resolve(args[0], validator);
            if (depthLevel == null) {
                return null;
            }
            return new ResolvedFunCall(
                new MembersFunDef(depthLevel, name.equalsIgnoreCase("AllMembers")),
                new Exp[] {new HierarchyExpr(depthLevel.hierarchy)},
                new SetType(MemberType.forHierarchy(depthLevel.hierarchy)));
        }
        if (call.getSyntax() == Syntax.Function && name.equalsIgnoreCase("Descendants")
            && (args.length == 2 || args.length == 3))
        {
            final DepthLevel depthLevel = resolve(args[1], validator);
            if (depthLevel == null) {
                return null;
            }
            Flag flag = Flag.SELF;
            if (args.length == 3) {
                flag = flag(args[2]);
                if (flag == null) {
                    return null;
                }
            }
            final Exp arg = validator.validate(args[0], false);
            final Type type = arg.getType();
            final Type elementType =
                type instanceof SetType ? ((SetType) type).getElementType() : type;
            if (!(elementType instanceof MemberType)) {
                throw Util.newError(
                    "Descendants with " + depthLevel.uniqueName
                    + " takes a member or a set of members of " + depthLevel.hierarchy.getUniqueName());
            }
            return new ResolvedFunCall(
                new DescendantsFunDef(depthLevel, flag),
                new Exp[] {arg},
                new SetType(MemberType.forHierarchy(depthLevel.hierarchy)));
        }
        return null;
    }

    private static Flag flag(Exp exp) {
        if (!(exp instanceof Id)) {
            return null;
        }
        final List<Id.Segment> segments = ((Id) exp).getSegments();
        if (segments.size() != 1 || !(segments.get(0) instanceof Id.NameSegment)) {
            return null;
        }
        try {
            return Flag.valueOf(
                ((Id.NameSegment) segments.get(0)).getName().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolves {@code <hierarchy>.[Level NN]} to a per-depth level, or null when the expression is
     * not one: not such a name, an element of that name exists, or the hierarchy is not
     * parent-child.
     */
    static DepthLevel resolve(Exp exp, Validator validator) {
        if (!(exp instanceof Id)) {
            return null;
        }
        final List<Id.Segment> segments = ((Id) exp).getSegments();
        if (segments.size() < 2) {
            return null;
        }
        final Id.Segment last = segments.get(segments.size() - 1);
        if (!(last instanceof Id.NameSegment)) {
            return null;
        }
        final Matcher matcher = NAME.matcher(((Id.NameSegment) last).getName().trim());
        if (!matcher.matches()) {
            return null;
        }
        final int number = Integer.parseInt(matcher.group(1));
        final SchemaReader schemaReader = validator.getSchemaReader();
        final Cube cube = validator.getQuery().getCube();
        if (cube == null) {
            return null;
        }
        if (lookup(schemaReader, cube, segments) != null) {
            return null;
        }
        final OlapElement parent =
            lookup(schemaReader, cube, segments.subList(0, segments.size() - 1));
        Hierarchy hierarchy = null;
        if (parent instanceof Hierarchy) {
            hierarchy = (Hierarchy) parent;
        } else if (parent instanceof Dimension) {
            // [Employees] names the dimension; its hierarchy of the same name is the user
            // hierarchy (the attribute model adds attribute hierarchies beside it).
            final Dimension dimension = (Dimension) parent;
            for (Hierarchy candidate : dimension.getHierarchies()) {
                if (Util.equalName(candidate.getName(), dimension.getName())) {
                    hierarchy = candidate;
                }
            }
            if (hierarchy == null && dimension.getHierarchies().length == 1) {
                hierarchy = dimension.getHierarchies()[0];
            }
        }
        if (hierarchy == null) {
            return null;
        }
        Level parentChildLevel = null;
        for (Level level : hierarchy.getLevels()) {
            if (level.isAll()) {
                continue;
            }
            if (parentChildLevel != null
                || !(level instanceof RolapLevel)
                || !((RolapLevel) level).isParentChild())
            {
                return null;
            }
            parentChildLevel = level;
        }
        if (parentChildLevel == null) {
            return null;
        }
        final int rootDepth = hierarchy.hasAll() ? 1 : 0;
        return new DepthLevel(
            hierarchy,
            parentChildLevel,
            rootDepth + number - 1,
            Util.makeFqName(hierarchy, String.format("Level %02d", number)));
    }

    private static OlapElement lookup(
        SchemaReader schemaReader, Cube cube, List<Id.Segment> segments)
    {
        try {
            return Util.lookupCompound(
                schemaReader, cube, segments, false, Category.Unknown);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code <per-depth level>.MEMBERS} and {@code .ALLMEMBERS}: the members at that depth. */
    static final class MembersFunDef extends FunDefBase {
        private final DepthLevel depthLevel;
        private final boolean includeCalculated;

        MembersFunDef(DepthLevel depthLevel, boolean includeCalculated) {
            super(
                includeCalculated ? "ParentChildLevelAllMembers" : "ParentChildLevelMembers",
                "The members at one depth of a parent-child hierarchy, "
                + depthLevel.uniqueName + (includeCalculated ? ".ALLMEMBERS" : ".MEMBERS"),
                "fxh");
            this.depthLevel = depthLevel;
            this.includeCalculated = includeCalculated;
        }

        public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
            return new AbstractListCalc(call, new Calc[0]) {
                public TupleList evaluateList(Evaluator evaluator) {
                    final List<Member> members = new ArrayList<Member>();
                    for (Member member
                        : getNonEmptyLevelMembers(
                            evaluator, depthLevel.level, includeCalculated))
                    {
                        if (member.getDepth() == depthLevel.treeDepth
                            && (includeCalculated || !member.isCalculated()))
                        {
                            members.add(member);
                        }
                    }
                    return mondrian.olap.fun.sort.Sorter.hierarchizeTupleList(
                        new UnaryTupleList(members), false);
                }
            };
        }
    }

    /**
     * {@code DESCENDANTS(<member or set>, <per-depth level>[, <flag>])}, by tree depth: SELF the
     * descendants at the level's depth, BEFORE those above it (the member included), AFTER those
     * below it, LEAVES the leaves at that depth or above it. A set's results are united, each
     * member once, in the order of the set.
     */
    static final class DescendantsFunDef extends FunDefBase {
        private final DepthLevel depthLevel;
        private final Flag flag;

        DescendantsFunDef(DepthLevel depthLevel, Flag flag) {
            super(
                "ParentChildLevelDescendants",
                "Descendants by " + depthLevel.uniqueName + ", " + flag,
                "fxx");
            this.depthLevel = depthLevel;
            this.flag = flag;
        }

        public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
            final Exp arg = call.getArg(0);
            if (arg.getType() instanceof SetType) {
                final ListCalc listCalc = compiler.compileList(arg);
                return new AbstractListCalc(call, new Calc[] {listCalc}) {
                    public TupleList evaluateList(Evaluator evaluator) {
                        final Set<Member> result = new LinkedHashSet<Member>();
                        final SchemaReader schemaReader = evaluator.getSchemaReader();
                        for (Member member
                            : listCalc.evaluateList(evaluator).slice(0))
                        {
                            descendants(member, schemaReader, result);
                        }
                        return new UnaryTupleList(new ArrayList<Member>(result));
                    }
                };
            }
            final MemberCalc memberCalc = compiler.compileMember(arg);
            return new AbstractListCalc(call, new Calc[] {memberCalc}) {
                public TupleList evaluateList(Evaluator evaluator) {
                    final Set<Member> result = new LinkedHashSet<Member>();
                    descendants(
                        memberCalc.evaluateMember(evaluator),
                        evaluator.getSchemaReader(),
                        result);
                    return new UnaryTupleList(new ArrayList<Member>(result));
                }
            };
        }

        private void descendants(Member member, SchemaReader schemaReader, Set<Member> result) {
            if (member == null || member.isNull()) {
                return;
            }
            if (!member.getHierarchy().equals(depthLevel.hierarchy)) {
                throw Util.newError(
                    "Descendants with " + depthLevel.uniqueName + " takes members of "
                    + depthLevel.hierarchy.getUniqueName() + ", not "
                    + member.getUniqueName());
            }
            visit(member, schemaReader, result);
        }

        private void visit(Member member, SchemaReader schemaReader, Set<Member> result) {
            final int depth = member.getDepth();
            final int target = depthLevel.treeDepth;
            if (flag.leaves) {
                if (depth > target) {
                    return;
                }
                final List<Member> children = schemaReader.getMemberChildren(member);
                if (children.isEmpty()) {
                    if (!member.isAll()) {
                        result.add(member);
                    }
                    return;
                }
                if (depth < target) {
                    for (Member child : children) {
                        visit(child, schemaReader, result);
                    }
                }
                return;
            }
            if ((depth < target && flag.before)
                || (depth == target && flag.self)
                || (depth > target && flag.after))
            {
                result.add(member);
            }
            if (depth < target || flag.after) {
                for (Member child : schemaReader.getMemberChildren(member)) {
                    visit(child, schemaReader, result);
                }
            }
        }
    }
}

// End ParentChildDepthLevels.java

package com.wizardmagic.common.spell;

import com.wizardmagic.api.elements.ElementAccess;
import com.wizardmagic.api.elements.ElementTypes;
import com.wizardmagic.core.spell.SpellElements;
import com.wizardmagic.core.spell.script.Host;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 把 {@link Host} 接到真实的 {@link ServerLevel}。
 *
 * <p>只在 {@code on cast} 里被调用（{@code tick} 阶段的调用在编译期就被拒了），
 * 所以这里的开销不影响每步模拟。</p>
 *
 * <p>元素场只存四个基础元素：融合元素（火+风…）目前返回 0，等 elements.json 补
 * composition 再用它反查基础组成。这一点和 {@code SpellElements} 的现状一致。</p>
 */
public final class McHost implements Host
{
    private final ServerLevel level;
    private final double hereX;
    private final double hereY;
    private final double hereZ;
    private final double lookX;
    private final double lookY;
    private final double lookZ;

    public McHost(ServerLevel level, Vec3 here)
    {
        this(level, here, 0.0, 0.0, 1.0);
    }

    public McHost(ServerLevel level, Vec3 here, Vec3 look)
    {
        this.level = level;
        this.hereX = here.x;
        this.hereY = here.y;
        this.hereZ = here.z;
        double len = look.length();
        if (!(len > 1.0E-6))
        {
            look = new Vec3(0.0, 0.0, 1.0);
            len = 1.0;
        }
        this.lookX = look.x / len;
        this.lookY = look.y / len;
        this.lookZ = look.z / len;
    }

    public McHost(ServerLevel level, Vec3 here, double lookX, double lookY, double lookZ)
    {
        this(level, here, new Vec3(lookX, lookY, lookZ));
    }

    @Override
    public double field(int elementId, double x, double y, double z)
    {
        ElementTypes type = base(elementId);
        if (type == null)
        {
            return 0.0;
        }
        return ElementAccess.get(level, BlockPos.containing(x, y, z), type)
                / (double) ElementTypes.MAX_VALUE;
    }

    @Override
    public double light(double x, double y, double z)
    {
        return level.getMaxLocalRawBrightness(BlockPos.containing(x, y, z)) / 15.0;
    }

    @Override
    public int weather()
    {
        return level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
    }

    @Override
    public long dayTime()
    {
        return level.getDayTime() % 24000L;
    }

    @Override
    public double hereX()
    {
        return hereX;
    }

    @Override
    public double hereY()
    {
        return hereY;
    }

    @Override
    public double hereZ()
    {
        return hereZ;
    }

    @Override
    public double lookX()
    {
        return lookX;
    }

    @Override
    public double lookY()
    {
        return lookY;
    }

    @Override
    public double lookZ()
    {
        return lookZ;
    }

    /** 融合元素目前不映射到基础元素场 */
    private static ElementTypes base(int elementId)
    {
        String id = SpellElements.id(elementId);
        if (id == null)
        {
            return null;
        }
        switch (id)
        {
            case "earth": return ElementTypes.Earth;
            case "water": return ElementTypes.Water;
            case "air": return ElementTypes.Wind;
            case "fire": return ElementTypes.Fire;
            default: return null;
        }
    }
}

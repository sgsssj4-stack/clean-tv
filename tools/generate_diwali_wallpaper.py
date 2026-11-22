import math
import random
from PIL import Image, ImageDraw, ImageFilter

WIDTH, HEIGHT = 1920, 1080

# 1. Base Gradient: Deep royal navy to dark warm burgundy-amber
img = Image.new("RGBA", (WIDTH, HEIGHT), (7, 6, 18, 255))
draw = ImageDraw.Draw(img)

for y in range(HEIGHT):
    # vertical gradient
    t = y / float(HEIGHT)
    # Top: #060511, Mid: #0F081C, Bottom: #1A0A16
    r = int(6 * (1 - t) + 26 * t)
    g = int(5 * (1 - t) + 10 * t)
    b = int(17 * (1 - t) + 22 * t)
    draw.line([(0, y), (WIDTH, y)], fill=(r, g, b, 255))

# 2. Golden ambient horizon glow at the bottom
horizon = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
h_draw = ImageDraw.Draw(horizon)
cx, cy = WIDTH // 2, HEIGHT - 50
max_radius = 900
for r_step in range(max_radius, 0, -30):
    alpha = int(45 * (1.0 - r_step / max_radius))
    # warm amber gold (255, 178, 60)
    h_draw.ellipse([cx - r_step, cy - int(r_step * 0.4), cx + r_step, cy + int(r_step * 0.4)], fill=(255, 160, 40, alpha))

horizon = horizon.filter(ImageFilter.GaussianBlur(radius=60))
img = Image.alpha_composite(img, horizon)

# 3. Soft golden bokeh lights (floating lanterns / festival sparkle)
bokeh = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
b_draw = ImageDraw.Draw(bokeh)
rnd = random.Random(108)

for _ in range(70):
    bx = rnd.randint(50, WIDTH - 50)
    by = rnd.randint(40, HEIGHT - 150)
    br = rnd.randint(6, 45)
    b_alpha = rnd.randint(15, 65)
    # Warm gold and amber tones
    color = (255, rnd.randint(180, 225), rnd.randint(60, 120), b_alpha)
    b_draw.ellipse([bx - br, by - br, bx + br, by + br], fill=color)

bokeh = bokeh.filter(ImageFilter.GaussianBlur(radius=8))
img = Image.alpha_composite(img, bokeh)

# 4. Sacred Rangoli / Mandala Watermark (Golden Geometry) in center
mandala = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
m_draw = ImageDraw.Draw(mandala)
mc_x, mc_y = WIDTH // 2, HEIGHT // 2 - 20

# Petals and concentric circles
gold_color = (255, 200, 80, 22) # subtle 8-9% opacity watermark
gold_line = (255, 215, 100, 35)

for r_ring in [60, 120, 180, 240, 300, 360]:
    m_draw.ellipse([mc_x - r_ring, mc_y - r_ring, mc_x + r_ring, mc_y + r_ring], outline=gold_line, width=2)

# 16-point star / floral pattern
num_petals = 16
for i in range(num_petals):
    angle = 2 * math.pi * i / num_petals
    px = mc_x + int(240 * math.cos(angle))
    py = mc_y + int(240 * math.sin(angle))
    pr = 55
    m_draw.ellipse([px - pr, py - pr, px + pr, py + pr], outline=gold_color, width=2)

# 8 larger outer arcs
for i in range(8):
    angle = 2 * math.pi * i / 8
    px = mc_x + int(330 * math.cos(angle))
    py = mc_y + int(330 * math.sin(angle))
    pr = 90
    m_draw.ellipse([px - pr, py - pr, px + pr, py + pr], outline=gold_color, width=2)

mandala = mandala.filter(ImageFilter.GaussianBlur(radius=1))
img = Image.alpha_composite(img, mandala)

# 5. Vignette darkening edges for rich TV contrast
vignette = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
v_draw = ImageDraw.Draw(vignette)
for y in range(HEIGHT):
    edge_y = min(y, HEIGHT - 1 - y) / float(HEIGHT * 0.3)
    edge_y = min(1.0, max(0.0, edge_y))
    alpha_y = int((1.0 - edge_y) * 90)
    if alpha_y > 0:
        v_draw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, alpha_y))

img = Image.alpha_composite(img, vignette)

# Save as high-quality JPEG
rgb_img = img.convert("RGB")
rgb_img.save("diwali_wallpaper.jpg", "JPEG", quality=95)
print("Generated diwali_wallpaper.jpg (1920x1080)")

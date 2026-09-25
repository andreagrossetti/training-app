// Case for the one-button workout remote: XIAO nRF52840 + LiPo 402030 + 12x12 tactile button.
// Wear it on the wrist with a 20 mm velcro strap through the end slots, or hang it from a cord.
//
// Parts (set `part` or use the Customizer): "base", "lid", "print" (both, laid out for printing),
// "assembly" (preview only). Both parts print without supports: base open side up, lid top face
// down. PETG recommended (it takes the lid's press fit better than PLA).
//
// Measure your parts and adjust the parameters below: the defaults are datasheet values.

part = "print"; // [print, base, lid, assembly]

/* [Electronics] */
battery = [30, 20, 4.2];     // LiPo 402030: length, width, thickness
xiao = [21, 17.8, 1.2];      // XIAO board: length, width, PCB thickness
tape = 0.6;                  // foam tape between battery and XIAO
usb_center_above_pcb = 1.6;  // USB-C connector centre above the PCB top
button_body = [12, 12, 3.8]; // tactile switch body (without the stem)
stem_top = 7.3;              // switch base to top of stem
cap_d = 11.6;                // round cap diameter
cap_h = 5.5;                 // cap total height
cap_socket = 3.0;            // how deep the stem goes into the cap
cap_protrude = 1.5;          // how much the cap stands out of the lid (less = fewer accidental presses)

/* [Case] */
wall = 1.6;
floor_t = 1.4;
lid_t = 2.0;
lip_h = 2.0;                 // lid lip that goes inside the base
lip_t = 1.2;
fit = 0.2;                   // clearance of the lid's press fit
wires = 2.5;                 // room under the button for its pins and wires
cavity_l = 36;
cavity_w = 22;

/* [Strap] */
strap_w = 20;                // strap width (slot is 1 mm wider)
strap_t = 2.5;               // strap thickness (slot is 0.7 mm wider)
wing_l = 8;
wing_t = 3;

$fn = 64;

// Derived heights (z = 0 at the top of the floor, inside the cavity).
cap_top_above_base = stem_top - cap_socket + cap_h;
boss_t = cap_top_above_base - cap_protrude - button_body.z;   // lid thickness around the button
button_base_z = battery.z + wires;
cavity_h = button_base_z + button_body.z + boss_t - lid_t;
outer = [cavity_l + 2 * wall, cavity_w + 2 * wall, floor_t + cavity_h];
button_x = cavity_l - lip_t - fit - button_body.x / 2 - 1.5;  // button centre, from the USB end
usb_z = floor_t + battery.z + tape + xiao.z + usb_center_above_pcb;

echo(str("Outside: ", outer.x, " x ", outer.y, " x ", outer.z + lid_t, " mm (cap +", cap_protrude, ")"));

module rounded_box(size, r = 2) {
    hull() for (x = [r, size.x - r], y = [r, size.y - r]) translate([x, y, 0]) cylinder(r = r, h = size.z);
}

module base() {
    difference() {
        union() {
            rounded_box(outer);
            // Strap wings at both ends, flush with the bottom.
            for (x = [-wing_l, outer.x - 2]) translate([x, 0, 0]) rounded_box([wing_l + 2, outer.y, wing_t]);
        }
        // Cavity.
        translate([wall, wall, floor_t]) cube([cavity_l, cavity_w, cavity_h + 1]);
        // Strap slots.
        for (x = [-wing_l / 2, outer.x + wing_l / 2])
            translate([x - (strap_t + 0.7) / 2, (outer.y - strap_w - 1) / 2, -1])
                cube([strap_t + 0.7, strap_w + 1, wing_t + 2]);
        // USB-C: opening for the connector, plus a recess for the plug's overmould.
        translate([-1, outer.y / 2, usb_z]) {
            rotate([0, 90, 0]) linear_extrude(wall + 2) offset(r = 1) square([4.2 - 2, 10 - 2], center = true);
            rotate([0, 90, 0]) linear_extrude(1 + 0.8) offset(r = 2) square([7.5 - 4, 13 - 4], center = true);
        }
        // Notch to pry the lid open.
        translate([outer.x / 2 - 4, -1, outer.z - 1]) cube([8, wall + 2, 2]);
    }
}

// Modelled in print orientation: top face on z = 0, the inside grows towards +z.
module lid() {
    inner = [cavity_l - 2 * fit, cavity_w - 2 * fit];
    hole_d = cap_d + 0.8;
    frame = button_body.x + 0.3 + 2 * 1.2;
    bx = wall + button_x;
    by = outer.y / 2;
    difference() {
        union() {
            rounded_box([outer.x, outer.y, lid_t]);
            // Lip.
            translate([wall + fit, wall + fit, lid_t]) difference() {
                cube([inner.x, inner.y, lip_h]);
                translate([lip_t, lip_t, -1]) cube([inner.x - 2 * lip_t, inner.y - 2 * lip_t, lip_h + 2]);
            }
            // Thicker around the button, then a frame holding the switch body.
            intersection() {
                translate([bx - frame / 2, by - frame / 2, lid_t]) cube([frame, frame, boss_t - lid_t + button_body.z * 0.8]);
                translate([wall + fit, wall + fit, 0]) cube([inner.x, inner.y, 50]);
            }
        }
        // Pocket for the switch body.
        translate([bx - (button_body.x + 0.3) / 2, by - (button_body.y + 0.3) / 2, boss_t])
            cube([button_body.x + 0.3, button_body.y + 0.3, 10]);
        // Cap hole, with a shallow dish around it so the thumb finds it.
        translate([bx, by, -1]) cylinder(d = hole_d, h = boss_t + 2);
        translate([bx, by, -0.01]) cylinder(d1 = hole_d + 4, d2 = hole_d, h = 1);
    }
}

module assembly() {
    base();
    translate([0, outer.y, outer.z + lid_t]) mirror([0, 0, 1]) mirror([0, 1, 0]) lid();
    // Electronics, for checking clearances.
    %translate([wall + (cavity_l - battery.x) / 2, wall + (cavity_w - battery.y) / 2, floor_t]) cube(battery);
    %translate([wall, wall + (cavity_w - xiao.y) / 2, floor_t + battery.z + tape]) cube([xiao.x, xiao.y, xiao.z + 3.3]);
    %translate([wall + button_x - 6, outer.y / 2 - 6, floor_t + button_base_z]) cube(button_body);
}

if (part == "base") base();
else if (part == "lid") lid();
else if (part == "assembly") assembly();
else {
    base();
    translate([0, outer.y + 10, 0]) lid();
}
